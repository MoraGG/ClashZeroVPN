package com.clashzerovpn.vpn

import android.util.Log
import com.clashzerovpn.engine.ClashEngine
import com.clashzerovpn.engine.ZeroTierEngine
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 包分发器：解析 TUN 读取的 IP 包，按 CIDR 规则分流至 Clash 或 ZeroTier。
 * 与 CZVpnService 协同：
 *   - TUN 读：CZVpnService.tunLoop → injectTunPacket → dispatch → 各自 engine.injectPacket
 *   - TUN 写：engine.onOutboundPacket 回调 → CZVpnService.writeToTun
 */
class PacketDispatcher(
    private val clashEngine: ClashEngine,
    private val zeroTierEngine: ZeroTierEngine,
    ztSubnets: List<String>,
) {

    private val running = AtomicBoolean(true)

    private val ztSubnets: List<CidrSubnet> by lazy {
        ztSubnets.mapNotNull { CidrSubnet.parse(it) }
    }

    fun stop() {
        running.set(false)
    }

    fun injectTunPacket(packet: ByteArray) {
        val dstIp = parseDstIp(packet) ?: return run {
            Log.v(TAG, "skip non-IPv4 packet, len=${packet.size}")
        }
        when {
            ztSubnets.any { it.contains(dstIp) } -> {
                if (zeroTierEngine.isRunning()) {
                    zeroTierEngine.injectPacket(packet)
                } else {
                    Log.v(TAG, "ZT engine not running, drop packet to $dstIp")
                }
            }
            else -> {
                if (clashEngine.isRunning()) {
                    clashEngine.injectPacket(packet)
                } else {
                    Log.v(TAG, "Clash engine not running, drop packet to $dstIp")
                }
            }
        }
    }

    private fun parseDstIp(packet: ByteArray): Inet4Address? {
        try {
            if (packet.size < 20) return null
            val ver = (packet[0].toInt() shr 4) and 0x0F
            if (ver != 4) return null
            val dst = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
            return InetAddress.getByAddress(dst) as? Inet4Address
        } catch (_: Exception) { return null }
    }

    private class CidrSubnet(private val network: Int, private val mask: Int) {
        fun contains(addr: Inet4Address): Boolean {
            val ip = ByteBuffer.wrap(addr.address).int
            return (ip and mask) == (network and mask)
        }
        companion object {
            fun parse(cidr: String): CidrSubnet? {
                return try {
                    val parts = cidr.split("/")
                    val ipBytes = InetAddress.getByName(parts[0]).address
                    val ipInt = ByteBuffer.wrap(ipBytes).int
                    val bits = parts[1].toInt()
                    val mask = if (bits == 0) 0 else (0xFFFFFFFF.toInt() shl (32 - bits))
                    CidrSubnet(ipInt, mask)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid CIDR: $cidr")
                    null
                }
            }
        }
    }

    companion object { private const val TAG = "CZ.Dispatcher" }
}
