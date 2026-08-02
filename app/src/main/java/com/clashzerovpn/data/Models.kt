package com.clashzerovpn.data

data class VpnProfile(
    var clashConfigPath: String = "",
    var zeroTierNetworkId: String = "",
    var zeroTierCidrs: List<String> = listOf(
        "192.168.0.0/16",
        "10.0.0.0/8",
        "172.16.0.0/12"
    ),
    var tunMtu: Int = 9000,
    var tunAddress: String = "172.19.0.1/30",
    var vpnAddr: String = "172.19.0.1",
    var vpnPrefix: Int = 30,
    var mihomoPort: Int = 7890,
    var dns1: String = "8.8.8.8",
    var dns2: String = "1.1.1.1",
    var ztSubnets: List<String> = listOf(
        "192.168.0.0/16",
        "10.0.0.0/8",
        "172.16.0.0/12"
    ),
)

data class TrafficStats(
    var rxBytesPerSec: Long = 0,
    var txBytesPerSec: Long = 0,
    var totalRxBytes: Long = 0,
    var totalTxBytes: Long = 0
)

enum class VpnState {
    DISCONNECTED, CONNECTING, CONNECTED, FAILED
}
