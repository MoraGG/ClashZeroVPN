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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

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
        if (vpnJob?.isActive == true) {
            Log.d(TAG, "VPN already running, skip startInternal")
            return
        }
        Log.d(TAG, "=== startInternal BEGIN ===")

        try {
            _state.value = VpnState.CONNECTING
            Log.d(TAG, "Step1: load profile")
            profile = ProfileStore(this).load()
            Log.d(TAG, "Step1 OK: clashConfigPath=${profile.clashConfigPath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Step1 FAILED: ${t.message}", t)
            _state.value = VpnState.FAILED
            return
        }

        val fd: android.os.ParcelFileDescriptor
        try {
            Log.d(TAG, "Step2: establish TUN")
            fd = establishTun(profile)
            tunFd = fd
            Log.d(TAG, "Step2 OK: TUN fd=${fd.fd}")
        } catch (t: Throwable) {
            Log.e(TAG, "Step2 FAILED: ${t.message}", t)
            _state.value = VpnState.FAILED
            return
        }

        try {
            Log.d(TAG, "Step3: create engines")
            clashEngine = ClashEngine().apply { onOutboundPacket = { writeToTun(it) } }
            zeroTierEngine = ZeroTierEngine().apply { onOutboundPacket = { writeToTun(it) } }
            lastClashEngine = clashEngine
            lastZeroTierEngine = zeroTierEngine
            Log.d(TAG, "Step3 OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Step3 FAILED: ${t.message}", t)
            runCatching { tunFd?.close() }
            tunFd = null
            _state.value = VpnState.FAILED
            return
        }

        try {
            Log.d(TAG, "Step4: PacketDispatcher init")
            dispatcher = PacketDispatcher(
                clashEngine = clashEngine,
                zeroTierEngine = zeroTierEngine,
                ztSubnets = profile.ztSubnets
            )
            Log.d(TAG, "Step4 OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Step4 FAILED: ${t.message}", t)
            runCatching { tunFd?.close() }
            tunFd = null
            _state.value = VpnState.FAILED
            return
        }

        try {
            Log.d(TAG, "Step5: clashEngine.init(home=${filesDir.absolutePath}, config=${profile.clashConfigPath}, port=${profile.mihomoPort})")
            clashEngine.init(filesDir.absolutePath, profile.clashConfigPath, profile.mihomoPort)
            Log.d(TAG, "Step5 OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Step5 FAILED: ${t.message}", t)
            runCatching { tunFd?.close() }
            tunFd = null
            _state.value = VpnState.FAILED
            return
        }

        if (profile.zeroTierNetworkId.isNotEmpty()) {
            try {
                Log.d(TAG, "Step6: zeroTierEngine.init()")
                zeroTierEngine.init(profile.zeroTierNetworkId, filesDir.absolutePath)
                Log.d(TAG, "Step6 OK")
            } catch (t: Throwable) {
                Log.e(TAG, "Step6 FAILED: ${t.message}", t)
                runCatching { tunFd?.close() }
                tunFd = null
                _state.value = VpnState.FAILED
                return
            }
        }

        try {
            Log.d(TAG, "Step7: clashEngine.start()")
            clashEngine.start()
            Log.d(TAG, "Step7 OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Step7 FAILED: ${t.message}", t)
            runCatching { tunFd?.close() }
            tunFd = null
            _state.value = VpnState.FAILED
            return
        }

        if (profile.zeroTierNetworkId.isNotEmpty()) {
            scope.launch {
                try {
                    zeroTierEngine.start()
                } catch (e: Exception) {
                    Log.e(TAG, "ZT start failed", e)
                }
            }
        }

        try {
            _state.value = VpnState.CONNECTED
            vpnJob = scope.launch { tunLoop(fd) }
            scope.launch { trafficTick() }
            Log.i(TAG, "=== VPN started successfully ===")
        } catch (t: Throwable) {
            Log.e(TAG, "Step8 (loop start) FAILED: ${t.message}", t)
            runCatching { tunFd?.close() }
            tunFd = null
            _state.value = VpnState.FAILED
        }
        Log.d(TAG, "=== startInternal END ===")
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
        val mtu = profile.tunMtu.coerceIn(1280, 32768)
        val buffer = ByteArray(mtu)
        var packetCount = 0L

        try {
            Log.d(TAG, "TUN read loop started, mtu=$mtu")
            while (true) {
                val n: Int = fis.read(buffer)
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

                dispatcher.injectTunPacket(pkt)

                if (packetCount % 5000 == 0L) {
                    Log.v(TAG, "Packets: $packetCount, rx: $totalRxBytes bytes")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TUN read loop error: ${e.message}", e)
        } finally {
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

        while (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) {
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
        Log.d(TAG, "  TunBuilder: session=${getString(com.clashzerovpn.R.string.app_name)}")
        Log.d(TAG, "  TunBuilder: address=${profile.vpnAddr}/${profile.vpnPrefix}, mtu=${profile.tunMtu}")

        val builder = Builder()
            .setSession(getString(com.clashzerovpn.R.string.app_name))
            .addAddress(profile.vpnAddr, profile.vpnPrefix)
            .setMtu(profile.tunMtu.coerceIn(1280, 1500))
            .setBlocking(true)

        configIntent()?.let { builder.setConfigureIntent(it) }

        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        builder.addDnsServer("198.18.0.1")
        if (profile.dns1.isNotEmpty()) builder.addDnsServer(profile.dns1)
        if (profile.dns2.isNotEmpty() && profile.dns2 != profile.dns1) {
            builder.addDnsServer(profile.dns2)
        }
        builder.addDisallowedApplication(packageName)
        builder.addSearchDomain("local")

        Log.d(TAG, "  TunBuilder: routes=0.0.0.0/0, DNS=198.18.0.1,${profile.dns1},${profile.dns2}")
        Log.d(TAG, "  TunBuilder: excludedApp=$packageName")

        val fd = builder.establish()
            ?: throw IllegalStateException("TUN establish() returned null")

        Log.i(TAG, "TUN established: fd=${fd.fd}")
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
