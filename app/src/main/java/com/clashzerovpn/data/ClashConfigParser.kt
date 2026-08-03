package com.clashzerovpn.data

import android.util.Log
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Clash 配置解析器
 * 用于解析 YAML 配置文件中的 proxies 和 proxy-groups
 */
class ClashConfigParser {

    companion object {
        private const val TAG = "ClashConfigParser"
    }

    /**
     * 解析 Clash 配置文件
     * @param configPath 配置文件路径
     * @return 解析后的配置对象，如果解析失败返回 null
     */
    fun parse(configPath: String): ClashConfig? {
        return try {
            val file = File(configPath)
            if (!file.exists()) {
                Log.e(TAG, "配置文件不存在: $configPath")
                return null
            }

            val yaml = Yaml()
            val configMap = yaml.load<Map<String, Any>>(file.inputStream())
                ?: return null.also { Log.e(TAG, "配置文件为空") }

            val proxies = parseProxies(configMap["proxies"])
            val proxyGroups = parseProxyGroups(configMap["proxy-groups"])

            ClashConfig(
                rawConfig = configMap,
                proxies = proxies,
                proxyGroups = proxyGroups,
                dnsEnabled = parseDnsEnabled(configMap["dns"]),
                mixedPort = parseInt(configMap["mixed-port"]) ?: 7890,
                socksPort = parseInt(configMap["socks-port"]) ?: 7891,
                redirPort = parseInt(configMap["redir-port"]) ?: 7892,
                allowLan = parseBoolean(configMap["allow-lan"]) ?: false,
                logLevel = parseString(configMap["log-level"]) ?: "info"
            ).also {
                Log.d(TAG, "成功解析配置: ${proxies.size} 个节点, ${proxyGroups.size} 个分组")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析配置失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 proxies 列表
     */
    private fun parseProxies(proxiesObj: Any?): List<ProxyNode> {
        if (proxiesObj !is List<*>) {
            Log.w(TAG, "proxies 不是列表类型: ${proxiesObj?.javaClass?.name}")
            return emptyList()
        }

        return proxiesObj.mapNotNull { item ->
            try {
                parseProxyNode(item as? Map<String, Any>)
            } catch (e: Exception) {
                Log.w(TAG, "解析节点失败: ${e.message}")
                null
            }
        }.also { nodes ->
            Log.d(TAG, "解析到 ${nodes.size} 个代理节点")
        }
    }

    /**
     * 解析单个代理节点
     */
    private fun parseProxyNode(map: Map<String, Any>?): ProxyNode? {
        if (map == null) return null

        val name = parseString(map["name"]) ?: return null
        val type = parseString(map["type"]) ?: "unknown"
        val server = parseString(map["server"])
        val port = parseInt(map["port"])

        return ProxyNode(
            name = name,
            type = type,
            server = server ?: "",
            port = port ?: 0,
            // SSR 特有字段
            cipher = parseString(map["cipher"]),
            password = parseString(map["password"]),
            protocol = parseString(map["protocol"]),
            protocolParam = parseString(map["protocol-param"]),
            obfs = parseString(map["obfs"]),
            obfsParam = parseString(map["obfs-param"]),
            // VMess 特有字段
            uuid = parseString(map["uuid"]),
            alterId = parseInt(map["alterId"]),
            security = parseString(map["security"]),
            // 其他可能字段
            rawData = map
        ).also { node ->
            Log.v(TAG, "解析节点: ${node.name} (${node.type})")
        }
    }

    /**
     * 解析 proxy-groups 列表
     */
    private fun parseProxyGroups(groupsObj: Any?): List<ProxyGroup> {
        if (groupsObj !is List<*>) {
            Log.w(TAG, "proxy-groups 不是列表类型")
            return emptyList()
        }

        return groupsObj.mapNotNull { item ->
            try {
                parseProxyGroup(item as? Map<String, Any>)
            } catch (e: Exception) {
                Log.w(TAG, "解析代理组失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 解析单个代理组
     */
    private fun parseProxyGroup(map: Map<String, Any>?): ProxyGroup? {
        if (map == null) return null

        val name = parseString(map["name"]) ?: return null
        val type = parseString(map["type"]) ?: "select"
        val proxies = parseGroupProxies(map["proxies"])

        return ProxyGroup(
            name = name,
            type = type,
            proxies = proxies
        )
    }

    /**
     * 解析组内的代理列表（可能是 DIRECT/REJECT 或节点名称）
     */
    private fun parseGroupProxies(proxiesObj: Any?): List<String> {
        if (proxiesObj !is List<*>) {
            return emptyList()
        }
        return proxiesObj.mapNotNull { parseString(it) }
    }

    /**
     * 解析 DNS 配置
     */
    private fun parseDnsEnabled(dnsObj: Any?): Boolean {
        if (dnsObj !is Map<*, *>) return true
        return parseBoolean(dnsObj["enable"]) ?: true
    }

    // ============ 工具方法 ============

    private fun parseString(value: Any?): String? {
        return when (value) {
            is String -> value
            is Number -> value.toString()
            else -> null
        }
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun parseBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
    }
}

/**
 * Clash 配置数据类
 */
data class ClashConfig(
    val rawConfig: Map<String, Any>,
    val proxies: List<ProxyNode>,
    val proxyGroups: List<ProxyGroup>,
    val dnsEnabled: Boolean,
    val mixedPort: Int,
    val socksPort: Int,
    val redirPort: Int,
    val allowLan: Boolean,
    val logLevel: String
) {
    /**
     * 获取可用的代理节点列表
     */
    fun getAvailableNodes(): List<ProxyNode> {
        return proxies.filter { it.server.isNotEmpty() && it.port > 0 }
    }

    /**
     * 根据类型获取节点
     */
    fun getNodesByType(type: String): List<ProxyNode> {
        return proxies.filter { it.type.equals(type, ignoreCase = true) }
    }

    /**
     * 获取默认代理组（通常用于全局代理）
     */
    fun getDefaultGroup(): ProxyGroup? {
        return proxyGroups.maxByOrNull { it.proxies.size }
    }
}

/**
 * 代理节点数据类
 */
data class ProxyNode(
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val cipher: String? = null,
    val password: String? = null,
    val protocol: String? = null,
    val protocolParam: String? = null,
    val obfs: String? = null,
    val obfsParam: String? = null,
    val uuid: String? = null,
    val alterId: Int? = null,
    val security: String? = null,
    val rawData: Map<String, Any>? = null
) {
    /**
     * 节点是否有效
     */
    fun isValid(): Boolean {
        return name.isNotEmpty() && server.isNotEmpty() && port > 0
    }

    /**
     * 获取节点类型描述
     */
    fun getTypeDisplayName(): String {
        return when (type.lowercase()) {
            "ss" -> "Shadowsocks"
            "ssr" -> "ShadowsocksR"
            "vmess" -> "VMess"
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "hysteria", "hysteria2" -> "Hysteria"
            "tuic" -> "TUIC"
            "wireguard" -> "WireGuard"
            else -> type.uppercase()
        }
    }

    /**
     * 获取节点信息摘要
     */
    fun getSummary(): String {
        return "$name\n类型: ${getTypeDisplayName()}\n服务器: $server:$port"
    }
}

/**
 * 代理组数据类
 */
data class ProxyGroup(
    val name: String,
    val type: String,
    val proxies: List<String>
) {
    /**
     * 获取组类型描述
     */
    fun getTypeDisplayName(): String {
        return when (type.lowercase()) {
            "select" -> "手动选择"
            "url-test" -> "自动测速"
            "fallback" -> "故障转移"
            "load-balance" -> "负载均衡"
            "DIRECT" -> "直接连接"
            "REJECT" -> "拒绝"
            else -> type
        }
    }
}
