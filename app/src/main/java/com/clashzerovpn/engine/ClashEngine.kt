package com.clashzerovpn.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.clashzerovpn.data.VpnProfile
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.TunInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Clash/Mihomo 引擎封装。
 *
 * 内核来源：ClashMetaForAndroid v2.11.32 官方内核
 *   - libclash.so（完整 Mihomo，gomobile 编译，含 Hysteria2/TUIC/WireGuard 等）
 *   - libbridge.so（JNI 桥接，暴露 Bridge.native* 接口）
 *
 * 解耦原理：不用 Mihomo 直接持有系统 TUN fd（那样就没法给 ZeroTier 分流了），
 * 而是用 ParcelFileDescriptor.createSocketPair() 创建一对双向 fd：
 *
 *   pfd[0] → 交给 Mihomo (Bridge.nativeStartTun)，它以为这就是 TUN 设备
 *   pfd[1] → 我们持有：
 *      - injectPacket(): 将系统 TUN 读到的公网流量写进 pfd[1] 喂给 Mihomo
 *      - loopbackRead(): 从 pfd[1] 读 Mihomo 输出的包 → onOutboundPacket 回写系统 TUN
 */
class ClashEngine : VpnEngine {

    override val name: String = "Clash-Mihomo"
    private var running = false
    private var onOutbound: ((ByteArray) -> Unit)? = null
    @Volatile var onOutboundPacket: ((ByteArray) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isReadyFlow = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _isReadyFlow

    // socketpair: [0] -> Mihomo side, [1] -> our side
    private var pairPfd: Array<ParcelFileDescriptor>? = null
    private var writeFos: FileOutputStream? = null
    private var readFis: FileInputStream? = null
    private var loopbackJob: kotlinx.coroutines.Job? = null

    private var _homeDir: String? = null
    private var _configPath: String? = null

    companion object {
        private const val TAG = "CZ.ClashEngine"
    }

    fun init(homeDir: String, configPath: String, @Suppress("UNUSED_PARAMETER") port: Int) {
        _homeDir = homeDir
        _configPath = configPath
    }

    override fun start(
        context: Context,
        config: VpnProfile,
        onOutboundPacket: (ByteArray) -> Unit
    ): Boolean {
        onOutbound = onOutboundPacket
        this.onOutboundPacket = onOutboundPacket
        return startInternal(context, config.clashConfigPath, onOutboundPacket)
    }

    fun start() {
        val cb = onOutbound ?: onOutboundPacket
        if (cb == null) {
            Log.w(TAG, "onOutbound callback not set; refusing to start without it")
            return
        }
        try {
            startInternal(null, _configPath, cb)
        } catch (e: Throwable) {
            Log.e(TAG, "ClashEngine.start() threw: ${e.message}", e)
            throw e  // 让调用方收到异常
        }
    }

    private fun startInternal(
        context: Context?,
        explicitConfigPath: String?,
        callback: (ByteArray) -> Unit
    ): Boolean {
        return try {
            // 1) 确保 Bridge 初始化（加载 libbridge.so + nativeInit）
            Bridge.ensureInitialized()

            // 2) 创建 socketpair（双向 fd）— Android 公开 API，API 19+
            val pfds = ParcelFileDescriptor.createReliableSocketPair()
            pairPfd = pfds
            readFis = FileInputStream(pfds[1].fileDescriptor)
            writeFos = FileOutputStream(pfds[1].fileDescriptor)

            // 3) 准备 clash home + config
            val ctx: android.content.Context = context
                ?: com.github.kr328.clash.common.Global.application
                ?: throw IllegalStateException("Global.application == null: CZApplication.onCreate() 未被调用，请确认 AndroidManifest.xml 注册了 CZApplication")
            val clashHome = File(_homeDir ?: (ctx.filesDir.absolutePath + File.separator + "clash"))
                .apply { if (!exists()) mkdirs() }
            listOf("cache", "logs", "profiles").forEach {
                File(clashHome, it).apply { if (!exists()) mkdirs() }
            }
            val resolvedConfigPath = explicitConfigPath
                .takeUnless { it.isNullOrEmpty() }
                ?: _configPath.takeUnless { it.isNullOrEmpty() }
            val configFile = resolvedConfigPath
                ?.takeIf { it.isNotEmpty() }
                ?.let { File(it).takeIf { f -> f.exists() } }
                ?: writeDefaultClashConfig(ctx)

            Log.d(TAG, "Clash home=${clashHome.absolutePath}, config=${configFile.absolutePath}")

            // 4) 加载配置
            val loadFuture = CompletableDeferred<Unit>()
            Bridge.nativeLoad(loadFuture, configFile.absolutePath)
            scope.launch {
                val ok = withTimeoutOrNull(30_000) { loadFuture.await() }
                if (ok == null) Log.w(TAG, "nativeLoad timeout, continue anyway")
                else Log.d(TAG, "nativeLoad completed")
            }

            // 5) 启动 Mihomo Tun 栈（把 pfd[0] 给 Mihomo 当作 TUN）
            val stack = "gvisor"
            val gateway = "198.18.0.1"
            val portal = "198.18.0.2"
            val dns = "198.18.0.1,1.1.1.1,8.8.8.8,223.5.5.5"
            Bridge.nativeStartTun(
                fd = pfds[0].fd,
                stack = stack,
                gateway = gateway,
                portal = portal,
                dns = dns,
                cb = object : TunInterface {
                    override fun markSocket(fd: Int) {
                        // 如果需要可以 VpnService.protect(fd) 防自环；此处默认不做
                    }
                    override fun querySocketUid(
                        protocol: Int,
                        source: String,
                        target: String
                    ): Int = -1
                }
            )

            // 6) 启动 Mihomo → 系统 TUN 回包协程
            loopbackJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(65535)
                val fis = readFis ?: return@launch
                Log.d(TAG, "loopback reader started")
                try {
                    while (running) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        callback(buf.copyOf(n))
                    }
                } catch (_: Exception) {
                } finally {
                    Log.d(TAG, "loopback reader ended")
                }
            }

            running = true
            _isReadyFlow.value = true
            Log.i(TAG, "Clash engine started (real Mihomo kernel via socketpair)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start Clash engine", e)
            throw e  // 传播错误，不回退
        }
    }

    override fun injectPacket(packet: ByteArray): Boolean {
        if (!running) return false
        val fos = writeFos ?: return true // stub 语义：未真实启动时返回成功
        return try {
            fos.write(packet)
            fos.flush()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "injectPacket write failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        running = false
        _isReadyFlow.value = false
        loopbackJob?.cancel()
        loopbackJob = null
        runCatching { Bridge.nativeStopTun() }
        runCatching { Bridge.nativeReset() }
        pairPfd?.forEach { pfd ->
            runCatching { pfd.close() }
        }
        pairPfd = null
        runCatching { readFis?.close() }; readFis = null
        runCatching { writeFos?.close() }; writeFos = null
        scope.cancel()
        onOutbound = null
        onOutboundPacket = null
        Log.i(TAG, "Clash engine stopped")
    }

    override fun isRunning(): Boolean = running

    private fun writeDefaultClashConfig(context: Context): File {
        val dir = File(context.filesDir, "clash")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "config.yaml")
        if (file.exists()) return file
        runCatching {
            context.assets.open("default_clash_config.yaml").use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
        }.getOrNull() ?: file.writeText(
            """
mixed-port: 7890
allow-lan: false
mode: rule
log-level: info
ipv6: false
dns:
  enable: true
  listen: 0.0.0.0:1053
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  fake-ip-filter:
    - '*'
  nameserver:
    - https://1.1.1.1/dns-query
    - https://223.5.5.5/dns-query
proxies: []
proxy-groups:
  - name: PROXY
    type: select
    proxies: [DIRECT]
rules:
  - GEOIP,CN,DIRECT
  - MATCH,DIRECT
            """.trimIndent()
        )
        return file
    }
}
