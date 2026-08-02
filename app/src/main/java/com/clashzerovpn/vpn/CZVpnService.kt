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
import kotlinx.coroutines.flow.SharedFlow
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
            clashEngine = ClashEngine().apply {
                onOutboundPacket = { writeToTun(it) }
            }
            zeroTierEngine = ZeroTierEngine().apply {
                onOutboundPacket = { writeToTun(it) }
            }
            lastClashEngine = clashEngine
            lastZeroTierEngine = zeroTierEngine
            dispatcher = PacketDispatcher(
                clashEngine = clashEngine,
                zeroTierEngine = zeroTierEngine,
                ztSubnets = profile.ztSubnets
            )
            clashEngine.init(filesDir.absolutePath, profile.clashConfigPath, profile.mihomoPort)
            if (profile.zeroTierNetworkId.isNotEmpty()) {
                zeroTierEngine.init(profile.zeroTierNetworkId, filesDir.absolutePath)
            }
            clashEngine.start()
            if (profile.zeroTierNetworkId.isNotEmpty()) {
                scope.launch {
                    try { zeroTierEngine.start() }
                    catch (e: Exception) { Log.e(TAG, "ZT start failed", e) }
                }
            }
            _state.value = VpnState.CONNECTED
            vpnJob = scope.launch { tunLoop(fd) }
            scope.launch { trafficTick() }
            Log.i(TAG, "VPN started")
        } catch (t: Throwable) {
            Log.e(TAG, "VPN start failed", t)
            _state.value = VpnState.FAILED
            stopSelf()
        }
    }

    private fun stopInternal() {
        _state.value = VpnState.DISCONNECTED
        runCatching { dispatcher.stop() }
        runCatching { clashEngine.stop() }
        runCatching { zeroTierEngine.stop() }
        runCatching { tunFd?.close() }
        tunFd = null
        vpnJob?.cancel()
        vpnJob = null
    }

    private suspend fun tunLoop(fd: android.os.ParcelFileDescriptor) {
        val fis = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(profile.tunMtu + 40)
        val target = Thread.currentThread()
        val watcher = thread(name = "cz-vpn-watcher", isDaemon = true) {
            while (target.isAlive) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@thread }
            }
        }
        try {
            while (true) {
                val n = fis.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val pkt = buffer.copyOf(n)
                totalRxBytes += n
                dispatcher.injectTunPacket(pkt)
            }
        } finally {
            watcher.interrupt()
            runCatching { fis.close() }
        }
    }

    @Volatile private var writeBufFos: FileOutputStream? = null
    @Synchronized private fun writeToTun(packet: ByteArray) {
        val fd = tunFd ?: return
        try {
            val fos = writeBufFos ?: FileOutputStream(fd.fileDescriptor).also { writeBufFos = it }
            fos.write(packet)
            fos.flush()
            totalTxBytes += packet.size
        } catch (t: Throwable) {
            Log.e(TAG, "writeToTun failed", t)
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

    private fun establishTun(profile: VpnProfile): android.os.ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(com.clashzerovpn.R.string.app_name))
            .addAddress(profile.vpnAddr, profile.vpnPrefix)
            .setMtu(profile.tunMtu)
            .setBlocking(true)
        configIntent()?.let { builder.setConfigureIntent(it) }
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        builder.addDnsServer(profile.dns1)
        if (profile.dns2 != profile.dns1) builder.addDnsServer(profile.dns2)
        return builder.establish() ?: throw IllegalStateException("TUN 建立失败")
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
