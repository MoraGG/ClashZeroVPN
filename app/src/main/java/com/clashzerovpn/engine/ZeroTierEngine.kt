package com.clashzerovpn.engine

import android.content.Context
import android.util.Log
import com.clashzerovpn.data.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ZeroTier 引擎封装。
 *
 * 工作流程：
 *   start() → nativeInit(storage) → 初始化 libzt + 缓存回调
 *           → nativeStart(networkId) → 后台线程 start node + join network
 *           → virtual wire reader 线程启动 (在 native)
 *           → 一旦 virtual wire 读出 IP 包,就通过 JNI 调 returnToTun() 回写到 TUN
 *   injectPacket(dstInZtCidr的包) → nativeInject → zts_virtual_wire_write() 进入虚拟网卡
 *
 * 两种模式 (CMake 里自动判断):
 *   - HAS_LIBZT=1 : 真正调用 libzt.so
 *   - HAS_LIBZT=0 : 空占位,方便不集成 libzt 的情况下也能编译和运行 (只是 ZT 不会实际发数据)
 */
class ZeroTierEngine : VpnEngine {

    override val name: String = "ZeroTier"
    private var running = false
    private var onOutbound: ((ByteArray) -> Unit)? = null
    @Volatile var onOutboundPacket: ((ByteArray) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isOnlineFlow = MutableStateFlow(false)
    val isOnlineFlow: StateFlow<Boolean> = _isOnlineFlow

    private val _nodeIdFlow = MutableStateFlow(0L)
    val nodeIdFlow: StateFlow<Long> = _nodeIdFlow

    /** 由 C++ native 层回调 (名字和签名必须与 native-lib.cpp 中匹配) */
    fun returnToTun(packet: ByteArray) {
        onOutbound?.invoke(packet)
        onOutboundPacket?.invoke(packet)
    }

    /** 由 C++ native 层回调：网络状态变化通知（libzt 1.16 事件驱动） */
    fun onNetworkUpdate() {
        // 查询网络状态并更新 UI
    }

    private var _networkId: String = ""
    private var _storagePath: String = ""

    /** 轻量 init，仅保存配置，启动时再真正调用 nativeStart */
    fun init(networkId: String, storagePath: String) {
        _networkId = networkId
        _storagePath = storagePath
    }

    // JNI 入口 (签名由 native-lib 的 Java_xxx 生成器依赖, 不可改名)
    private external fun nativeInit(storagePath: String): Int
    private external fun nativeStart(networkId: String): Int
    private external fun nativeInject(packet: ByteArray, len: Int): Int
    private external fun nativeStop()
    private external fun nativeIsOnline(): Boolean
    private external fun nativeGetNodeId(): Long

    companion object {
        private const val TAG = "CZ.ZTEngine"

        init {
            var loaded = false
            try {
                // 1) 优先加载我们自己构建的 czvpn_jni.so，它内部已经 link 了 libzt
                System.loadLibrary("czvpn_jni")
                loaded = true
                Log.d(TAG, "libczvpn_jni loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "czvpn_jni load failed: ${e.message}")
            }
            if (!loaded) {
                // fallback: 没构建 JNI 时, 留作占位 (之后用 stub)
                try {
                    System.loadLibrary("zt")
                    Log.i(TAG, "libzt loaded as fallback (no JNI bridge)")
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "no native ZeroTier libs available, running in stub-only mode")
                }
            }
        }
    }

    override fun start(
        context: Context,
        config: VpnProfile,
        onOutboundPacket: (ByteArray) -> Unit
    ): Boolean {
        if (config.zeroTierNetworkId.length != 16) {
            Log.w(TAG, "ZeroTier network id not set or invalid length(${config.zeroTierNetworkId.length}), skip engine")
            return false
        }
        onOutbound = onOutboundPacket
        return try {
            val storageDir = File(context.filesDir, "zerotier")
            if (!storageDir.exists()) storageDir.mkdirs()

            val initRc = safeCall { nativeInit(storageDir.absolutePath) }
            Log.d(TAG, "nativeInit rc=$initRc storage=${storageDir.absolutePath}")

            val startRc = safeCall { nativeStart(config.zeroTierNetworkId) }
            Log.d(TAG, "nativeStart rc=$startRc network=${config.zeroTierNetworkId}")

            running = true
            // 定期轮询 online 状态, 更新给 UI
            scope.launch {
                while (running) {
                    val online = runCatching { nativeIsOnline() }.getOrDefault(false)
                    val nid = runCatching { nativeGetNodeId() }.getOrDefault(0L)
                    _isOnlineFlow.value = online
                    if (nid != 0L) _nodeIdFlow.value = nid
                    kotlinx.coroutines.delay(5000)
                }
            }
            Log.i(TAG, "ZeroTier engine started (libzt.so loaded? $initRc != null)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start ZeroTier", e)
            false
        }
    }

    override fun injectPacket(packet: ByteArray): Boolean {
        if (!running) return false
        return try {
            val n = nativeInject(packet, packet.size)
            n > 0
        } catch (e: Throwable) {
            Log.w(TAG, "injectPacket failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        running = false
        scope.cancel()
        try { nativeStop() } catch (_: Throwable) {}
        onOutbound = null
        onOutboundPacket = null
        _isOnlineFlow.value = false
        _nodeIdFlow.value = 0L
        Log.i(TAG, "ZeroTier engine stopped")
    }

    override fun isRunning(): Boolean = running

    /** 简化版 start，无参数（使用 init 保存的配置），返回 false 时视为已以 stub 占位启动 */
    suspend fun start() {
        val nid = _networkId
        val store = _storagePath.ifEmpty {
            Log.w(TAG, "ZeroTier storagePath not set, skip native start")
            running = true
            return
        }
        if (nid.length != 16) {
            Log.w(TAG, "ZeroTier network id invalid, skip engine (stub start)")
            running = true
            return
        }
        try {
            val storageDir = File(store, "zerotier").apply { if (!exists()) mkdirs() }
            val initRc = safeCall { nativeInit(storageDir.absolutePath) }
            Log.d(TAG, "nativeInit rc=$initRc storage=${storageDir.absolutePath}")
            val startRc = safeCall { nativeStart(nid) }
            Log.d(TAG, "nativeStart rc=$startRc network=$nid")
            running = true
            scope.launch {
                while (running) {
                    val online = runCatching { nativeIsOnline() }.getOrDefault(false)
                    val id = runCatching { nativeGetNodeId() }.getOrDefault(0L)
                    _isOnlineFlow.value = online
                    if (id != 0L) _nodeIdFlow.value = id
                    kotlinx.coroutines.delay(5000)
                }
            }
            Log.i(TAG, "ZeroTier engine started")
        } catch (e: Throwable) {
            Log.e(TAG, "ZT simple start failed", e)
            running = true // 允许 stub 模式，不阻断其他引擎
        }
    }

    private inline fun <T> safeCall(block: () -> T): T? =
        runCatching { block() }
            .onFailure { e -> Log.w(TAG, "native call failed: ${e.message}") }
            .getOrNull()
}
