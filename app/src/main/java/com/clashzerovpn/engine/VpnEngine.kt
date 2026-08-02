package com.clashzerovpn.engine

/**
 * 抽象引擎接口：无论是 Clash 还是 ZeroTier，
 * 都需要启动、停止、接收/发送 IP 数据包。
 */
interface VpnEngine {

    /** 引擎名称，用于日志 */
    val name: String

    /**
     * 启动引擎
     * @param onOutboundPacket 当引擎需要向 TUN 回写包时回调
     *   (例如 ZeroTier 收到的内网响应、Clash 返回的代理响应)
     * @return 是否启动成功
     */
    fun start(
        context: android.content.Context,
        config: com.clashzerovpn.data.VpnProfile,
        onOutboundPacket: (ByteArray) -> Unit
    ): Boolean

    /**
     * 注入一个 IP 数据包（从 TUN 收到，目标匹配该引擎时调用）
     * @return 成功返回 true
     */
    fun injectPacket(packet: ByteArray): Boolean

    /** 停止引擎 */
    fun stop()

    /** 当前是否运行中 */
    fun isRunning(): Boolean
}
