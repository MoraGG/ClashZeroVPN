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
        Log.d(TAG, "ClashEngine.startInternal BEGIN")
        try {
            Log.d(TAG, "  SubStep 1: Bridge.ensureInitialized()")
            Bridge.ensureInitialized()
            Log.d(TAG, "  SubStep 1 OK")

            Log.d(TAG, "  SubStep 2: ParcelFileDescriptor.createReliableSocketPair()")
            val pfds = ParcelFileDescriptor.createReliableSocketPair()
            pairPfd = pfds
            readFis = FileInputStream(pfds[1].fileDescriptor)
            writeFos = FileOutputStream(pfds[1].fileDescriptor)
            Log.d(TAG, "  SubStep 2 OK: pfds=${pfds[0].fd},${pfds[1].fd}")

            Log.d(TAG, "  SubStep 3: preparing clash home")
            val ctx: android.content.Context = context
                ?: com.github.kr328.clash.common.Global.application
                ?: throw IllegalStateException("Global.application == null: CZApplication.onCreate() not called")
            val clashHome = File(_homeDir ?: (ctx.filesDir.absolutePath + File.separator + "clash"))
                .apply { if (!exists()) mkdirs() }
            listOf("cache", "logs", "profiles").forEach {
                File(clashHome, it).apply { if (!exists()) mkdirs() }
            }
            val resolvedConfigPath = explicitConfigPath
                ?.takeIf { it.isNotEmpty() }
                ?.let { File(it) }
                ?.let { if (it.isDirectory()) it else it.parentFile }
                ?: File(ctx.filesDir, "clash").also { it.mkdirs() }
            // nativeLoad 在 clashHome 下找 config.yaml，所以要复制到那里
            val clashHomeConfig = File(clashHome, "config.yaml")
            if (explicitConfigPath?.let { File(it).takeIf { f -> f.exists() } } != null) {
                File(explicitConfigPath!!).copyTo(clashHomeConfig, overwrite = true)
            } else if (!clashHomeConfig.exists()) {
                writeDefaultClashConfig(ctx, clashHomeConfig)
            }
            Log.d(TAG, "  SubStep 3 OK: home=${clashHome.absolutePath}, configDir=${resolvedConfigPath.absolutePath}, clashHomeConfig=${clashHomeConfig.absolutePath}")

            Log.d(TAG, "  SubStep 4: Bridge.nativeLoad()")
            val loadFuture = CompletableDeferred<Unit>()
            Bridge.nativeLoad(loadFuture, clashHome.absolutePath)
            Log.d(TAG, "  SubStep 4: waiting for nativeLoad to complete...")
            kotlinx.coroutines.runBlocking {
                val ok = withTimeoutOrNull(30_000L) { loadFuture.await() }
                if (ok == null) throw IllegalStateException("nativeLoad timeout")
            }
            Log.d(TAG, "  SubStep 4 OK: nativeLoad completed, now starting TUN")

            Log.d(TAG, "  SubStep 5: Bridge.nativeStartTun()")
            val stack = "gvisor"
            val gateway = "198.18.0.1/32"        // 必须带 CIDR 后缀
            val portal = "198.18.0.2/32"         // 必须带 CIDR 后缀
            val dns = "198.18.0.1,1.1.1.1,8.8.8.8,223.5.5.5"
            Bridge.nativeStartTun(
                fd = pfds[0].fd,
                stack = stack,
                gateway = gateway,
                portal = portal,
                dns = dns,
                cb = object : TunInterface {
                    override fun markSocket(fd: Int) {}
                    override fun querySocketUid(protocol: Int, source: String, target: String): Int = -1
                }
            )
            Log.d(TAG, "  SubStep 5 OK")

            Log.d(TAG, "  SubStep 6: starting loopback reader coroutine")
            loopbackJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(65535)
                val fis = readFis ?: return@launch
                Log.d(TAG, "  loopback reader started")
                try {
                    while (running) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        callback(buf.copyOf(n))
                    }
                } catch (_: Exception) {
                } finally {
                    Log.d(TAG, "  loopback reader ended")
                }
            }
            Log.d(TAG, "  SubStep 6 OK")

            running = true
            _isReadyFlow.value = true
            Log.i(TAG, "ClashEngine.startInternal OK")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "ClashEngine.startInternal FAILED: ${e.message}", e)
            throw e
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
        if (!running) return
        running = false
        _isReadyFlow.value = false
        loopbackJob?.cancel(); loopbackJob = null
        try {
            Bridge.nativeStopTun()
        } catch (e: Throwable) {
            Log.w(TAG, "nativeStopTun failed: ${e.message}")
        }
        try {
            Bridge.nativeReset()
        } catch (e: Throwable) {
            Log.w(TAG, "nativeReset failed: ${e.message}")
        }
        pairPfd?.forEach { try { it.close() } catch (_: Throwable) {} }; pairPfd = null
        try { readFis?.close() } catch (_: Throwable) {}; readFis = null
        try { writeFos?.close() } catch (_: Throwable) {}; writeFos = null
        onOutbound = null
        onOutboundPacket = null
        Log.i(TAG, "Clash engine stopped")
    }

    override fun isRunning(): Boolean = running

    private fun writeDefaultClashConfig(context: Context, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) return outputFile
        runCatching {
            context.assets.open("default_clash_config.yaml").use { input ->
                outputFile.outputStream().use { out -> input.copyTo(out) }
            }
        }.getOrNull() ?: outputFile.writeText(
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
        return outputFile
    }
}
