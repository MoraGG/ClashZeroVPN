package com.clashzerovpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.clashzerovpn.data.ProfileStore
import com.clashzerovpn.data.TrafficStats
import com.clashzerovpn.data.VpnProfile
import com.clashzerovpn.data.VpnState
import com.clashzerovpn.engine.ClashEngine
import com.clashzerovpn.engine.ZeroTierEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

class CZVpnService : VpnService() {

    private lateinit var profile: VpnProfile
    private var tunFd: android.os.ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clashEngine: ClashEngine
    private lateinit var zeroTierEngine: ZeroTierEngine
    private lateinit var dispatcher: PacketDispatcher
    private var totalRxBytes = 0L
    private var totalTxBytes = 0L

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
    }

    override fun onDestroy() {
        stopInternal()
        serviceRef?.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInternal()
            ACTION_STOP -> { stopInternal(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startInternal() {
        if (vpnJob != null && vpnJob?.isActive == true) return
        _state.value = VpnState.CONNECTING
        profile = ProfileStore(this).load()

        try {
            val fd = establishTun(profile)
            tunFd = fd

            // 初始化 Clash 引擎
            clashEngine = ClashEngine().apply {
                onOutboundPacket = { writeToTun(it) }
            }

            // 初始化 ZeroTier 引擎
            zeroTierEngine = ZeroTierEngine().apply {
                onOutboundPacket = { writeToTun(it) }
            }

            lastClashEngine = clashEngine
            lastZeroTierEngine = zeroTierEngine

            // 初始化数据包分发器
            dispatcher = PacketDispatcher(
                clashEngine = clashEngine,
                zeroTierEngine = zeroTierEngine,
                ztSubnets = profile.ztSubnets
            )

            // 初始化 Clash 配置
            clashEngine.init(
                filesDir.absolutePath,
                profile.clashConfigPath,
                profile.mihomoPort
            )

            // 初始化 ZeroTier
            if (profile.zeroTierNetworkId.isNotEmpty()) {
                zeroTierEngine.init(profile.zeroTierNetworkId, filesDir.absolutePath)
            }

            // 启动 Clash
            clashEngine.start()

            // 启动 ZeroTier
            if (profile.zeroTierNetworkId.isNotEmpty()) {
                scope.launch {
                    try {
                        zeroTierEngine.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "ZT start failed", e)
                    }
                }
            }

            _state.value = VpnState.CONNECTED
            vpnJob = scope.launch { tunLoop(fd) }
            scope.launch { trafficTick() }

            Log.i(TAG, "VPN started successfully")
            Log.d(TAG, "TUN FD: ${fd.fd}, MTU: ${profile.tunMtu}")
            Log.d(TAG, "DNS Servers: ${profile.dns1}, ${profile.dns2}")

        } catch (t: Throwable) {
            Log.e(TAG, "VPN start failed", t)
            _state.value = VpnState.FAILED
            stopSelf()
        }
    }

    private fun stopInternal() {
        _state.value = VpnState.DISCONNECTED

        Log.d(TAG, "Stopping VPN services...")

        runCatching { dispatcher.stop() }
        runCatching { clashEngine.stop() }
        runCatching { zeroTierEngine.stop() }
        runCatching { tunFd?.close() }

        tunFd = null
        vpnJob?.cancel()
        vpnJob = null

        Log.i(TAG, "VPN stopped")
    }

    private suspend fun tunLoop(fd: android.os.ParcelFileDescriptor) {
        val fis = FileInputStream(fd.fileDescriptor)
        // 使用合理的缓冲区大小
        val buffer = ByteArray(profile.tunMtu)
        val target = Thread.currentThread()

        // 线程监控，用于检测主线程是否异常退出
        val watcher = thread(name = "cz-vpn-watcher", isDaemon = true) {
            while (target.isAlive) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            Log.w(TAG, "VPN loop thread terminated unexpectedly")
        }

        try {
            Log.d(TAG, "TUN read loop started")
            var packetCount = 0

            while (true) {
                val n = fis.read(buffer)
                if (n < 0) {
                    Log.d(TAG, "TUN read returned EOF")
                    break
                }
                if (n == 0) {
                    continue
                }

                packetCount++
                val pkt = buffer.copyOf(n)
                totalRxBytes += n

                // 分发数据包到对应的引擎
                dispatcher.injectTunPacket(pkt)

                // 每 1000 个包打印一次日志
                if (packetCount % 1000 == 0) {
                    Log.v(TAG, "Processed $packetCount packets, total rx: $totalRxBytes bytes")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TUN read loop error", e)
        } finally {
            watcher.interrupt()
            runCatching { fis.close() }
            Log.d(TAG, "TUN read loop ended. Total packets: $packetCount")
        }
    }

    @Volatile private var writeBufFos: FileOutputStream? = null
    @Synchronized private fun writeToTun(packet: ByteArray) {
        val fd = tunFd ?: return

        try {
            val fos = writeBufFos ?: FileOutputStream(fd.fileDescriptor).also {
                writeBufFos = it
            }
            fos.write(packet)
            fos.flush()
            totalTxBytes += packet.size
        } catch (t: Throwable) {
            Log.e(TAG, "writeToTun failed: ${t.message}", t)
            writeBufFos = null
        }
    }

    private suspend fun trafficTick() {
        var lastRx = 0L
        var lastTx = 0L

        while (_state.value.let { it == VpnState.CONNECTED || it == VpnState.CONNECTING }) {
            delay(1000)
            val rx = totalRxBytes
            val tx = totalTxBytes

            _traffic.value = TrafficStats(
                rxBytesPerSec = (rx - lastRx).coerceAtLeast(0),
                txBytesPerSec = (tx - lastTx).coerceAtLeast(0),
                totalRxBytes = rx,
                totalTxBytes = tx,
            )
            lastRx = rx
            lastTx = tx
        }
    }

    /**
     * 建立 TUN 接口
     * 关键配置：
     * 1. 添加 Fake-IP DNS 服务器 (198.18.0.1)
     * 2. 添加全流量路由 (0.0.0.0/0)
     * 3. 设置合理的 MTU
     * 4. 排除 VPN 应用自身避免循环
     */
    private fun establishTun(profile: VpnProfile): android.os.ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(com.clashzerovpn.R.string.app_name))
            .addAddress(profile.vpnAddr, profile.vpnPrefix)
            .setMtu(profile.tunMtu.coerceIn(1280, 1500)) // MTU 范围限制
            .setBlocking(true)

        // 配置 Intent
        configIntent()?.let { builder.setConfigureIntent(it) }

        // 添加全流量路由 - 关键！否则流量不会走 VPN
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)

        // 添加 DNS 服务器 - 使用 Clash 的 Fake-IP 地址
        // 这是最重要的配置，决定了 DNS 解析是否工作
        builder.addDnsServer("198.18.0.1") // Clash Fake-IP DNS
        builder.addDnsServer(profile.dns1)
        if (profile.dns2 != profile.dns1) {
            builder.addDnsServer(profile.dns2)
        }

        // 排除 VPN 应用自身，避免流量循环
        // 这是防止 VPN 连接后无法上网的关键配置
        builder.addDisallowedApplication(packageName)

        // 尝试添加搜索域（可选）
        builder.addSearchDomain("local")

        Log.d(TAG, "Establishing TUN with settings:")
        Log.d(TAG, "  Address: ${profile.vpnAddr}/${profile.vpnPrefix}")
        Log.d(TAG, "  MTU: ${profile.tunMtu}")
        Log.d(TAG, "  DNS: 198.18.0.1, ${profile.dns1}, ${profile.dns2}")
        Log.d(TAG, "  Routes: 0.0.0.0/0, ::/0")
        Log.d(TAG, "  Excluded App: $packageName")

        val fd = builder.establish()
            ?: throw IllegalStateException("TUN 建立失败")

        Log.i(TAG, "TUN interface established, fd=${fd.fd}")

        return fd
    }

    private fun configIntent(): android.app.PendingIntent? {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getActivity(this, 0, i, flags)
    }

    companion object {
        private const val TAG = "CZVpnService"
        const val ACTION_START = "com.clashzerovpn.ACTION_START"
        const val ACTION_STOP = "com.clashzerovpn.ACTION_STOP"

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state

        private val _traffic = MutableStateFlow(TrafficStats(0, 0, 0, 0))
        val traffic: StateFlow<TrafficStats> = _traffic

        @Volatile private var serviceRef: WeakReference<CZVpnService>? = null
        @Volatile var lastClashEngine: ClashEngine? = null
        @Volatile var lastZeroTierEngine: ZeroTierEngine? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, CZVpnService::class.java).setAction(ACTION_START)
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, CZVpnService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
