package com.clashzerovpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray

class ProfileStore(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun load(): VpnProfile {
        val profile = VpnProfile()
        profile.clashConfigPath = prefs.getString(KEY_CLASH_CONFIG, "") ?: ""
        profile.zeroTierNetworkId = prefs.getString(KEY_ZT_NETWORK, "") ?: ""
        profile.tunMtu = prefs.getInt(KEY_TUN_MTU, 9000)
        profile.tunAddress = prefs.getString(KEY_TUN_ADDR, "172.19.0.1/30") ?: "172.19.0.1/30"
        val (addr, pref) = parseAddr(profile.tunAddress)
        profile.vpnAddr = addr
        profile.vpnPrefix = pref
        val cidrsStr = prefs.getString(KEY_ZT_CIDRS, null)
        if (!cidrsStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(cidrsStr)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                if (list.isNotEmpty()) {
                    profile.zeroTierCidrs = list
                    profile.ztSubnets = list
                }
            } catch (_: Exception) {}
        }
        // 单独读取 ztSubnets（用户自定义的 ZeroTier 内网网段）
        val subnetsStr = prefs.getString(KEY_ZT_SUBNETS, null)
        if (!subnetsStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(subnetsStr)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                if (list.isNotEmpty()) profile.ztSubnets = list
            } catch (_: Exception) {}
        }
        return profile
    }

    fun save(profile: VpnProfile) {
        val ed = prefs.edit()
        ed.putString(KEY_CLASH_CONFIG, profile.clashConfigPath)
        ed.putString(KEY_ZT_NETWORK, profile.zeroTierNetworkId)
        ed.putInt(KEY_TUN_MTU, profile.tunMtu)
        ed.putString(KEY_TUN_ADDR, profile.tunAddress)
        val arr = JSONArray()
        profile.zeroTierCidrs.forEach { arr.put(it) }
        ed.putString(KEY_ZT_CIDRS, arr.toString())
        val arr2 = JSONArray()
        profile.ztSubnets.forEach { arr2.put(it) }
        ed.putString(KEY_ZT_SUBNETS, arr2.toString())
        ed.apply()
    }

    private fun parseAddr(addr: String): Pair<String, Int> {
        val parts = addr.split("/")
        if (parts.size == 2) {
            val p = parts[1].toIntOrNull() ?: 30
            return parts[0] to p
        }
        return "172.19.0.1" to 30
    }

    companion object {
        private const val KEY_CLASH_CONFIG = "clash_config_path"
        private const val KEY_ZT_NETWORK = "zt_network_id"
        private const val KEY_ZT_CIDRS = "zt_cidrs"
        private const val KEY_ZT_SUBNETS = "zt_subnets"
        private const val KEY_TUN_MTU = "tun_mtu"
        private const val KEY_TUN_ADDR = "tun_addr"
    }
}
