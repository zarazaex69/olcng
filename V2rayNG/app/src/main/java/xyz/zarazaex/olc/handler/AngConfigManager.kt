package xyz.zarazaex.olc.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.Log
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.AppConfig.HY2
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.dto.SubscriptionCache
import xyz.zarazaex.olc.dto.SubscriptionItem
import xyz.zarazaex.olc.dto.SubscriptionUpdateResult
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.extension.isNotNullEmpty
import xyz.zarazaex.olc.fmt.CustomFmt
import xyz.zarazaex.olc.fmt.Hysteria2Fmt
import xyz.zarazaex.olc.fmt.ShadowsocksFmt
import xyz.zarazaex.olc.fmt.SocksFmt
import xyz.zarazaex.olc.fmt.TrojanFmt
import xyz.zarazaex.olc.fmt.VlessFmt
import xyz.zarazaex.olc.fmt.VmessFmt
import xyz.zarazaex.olc.fmt.WireguardFmt
import xyz.zarazaex.olc.util.HttpUtil
import xyz.zarazaex.olc.util.JsonUtil
import xyz.zarazaex.olc.util.QRCodeDecoder
import xyz.zarazaex.olc.util.Utils
import java.net.URI

object AngConfigManager {

    private val subscriptionLocks = mutableMapOf<String, Any>()

    private fun getSubscriptionLock(subid: String): Any {
        return synchronized(subscriptionLocks) {
            subscriptionLocks.getOrPut(subid) { Any() }
        }
    }


    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = V2rayConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.CUSTOM -> ""
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.HTTP -> ""
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                EConfigType.POLICYGROUP -> ""
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }

        var countSub = parseBatchSubscription(server)
        if (countSub <= 0) {
            countSub = parseBatchSubscription(Utils.decode(server))
        }
        if (countSub > 0) {
            updateConfigViaSubAll()
        }

        return count to countSub
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        return synchronized(getSubscriptionLock(subid)) {
            try {
                if (servers == null) {
                    return@synchronized 0
                }
                val removedSelected = if (subid.isNotBlank() && !append) {
                    MmkvManager.getSelectServer()
                        .takeIf { it?.isNotBlank() == true }
                        ?.let { MmkvManager.decodeServerConfig(it) }
                        ?.takeIf { it.subscriptionId == subid }
                } else {
                    null
                }

                val subItem = MmkvManager.decodeSubscription(subid)

                val oldServerData = if (!append) {
                    saveOldServerData(subid)
                } else {
                    emptyMap()
                }

                val configs = mutableListOf<ProfileItem>()
                servers.lines()
                    .distinct()
                    .reversed()
                    .forEach {
                        val config = parseConfig(it, subid, subItem)
                        if (config != null) {
                            configs.add(config)
                        }
                    }

                if (configs.isNotEmpty()) {
                    if (!append) {
                        MmkvManager.removeServerViaSubid(subid)
                    }
                    val keyToProfile = batchSaveConfigs(configs, subid, append)
                    restoreOldServerData(keyToProfile, oldServerData)
                    val matchKey = findMatchedProfileKey(keyToProfile, removedSelected)
                    matchKey?.let { MmkvManager.setSelectServer(it) }
                }

                return@synchronized configs.size
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse batch config", e)
            }
            return@synchronized 0
        }
    }

    /**
     * Batch save configurations to reduce serverList read/write operations.
     * Reads serverList once, saves all configs, then writes serverList once.
     *
     * @param configs The list of ProfileItem to save.
     * @param subid The subscription ID.
     * @return Map of generated keys to their corresponding ProfileItem.
     */
    private fun batchSaveConfigs(configs: List<ProfileItem>, subid: String, append: Boolean): Map<String, ProfileItem> {
        val keyToProfile = mutableMapOf<String, ProfileItem>()

        val serverList = if (append) {
            MmkvManager.decodeServerList(subid)
        } else {
            mutableListOf()
        }
        var needSetSelected = MmkvManager.getSelectServer().isNullOrBlank()

        val existingProfiles = if (append) {
            serverList.mapNotNull { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { guid to it }
            }.toMap()
        } else {
            emptyMap()
        }

        configs.forEach { config ->
            val existingKey = existingProfiles.entries.firstOrNull { (_, existing) ->
                existing == config
            }?.key

            if (existingKey != null) {
                config.isFavorite = existingProfiles[existingKey]?.isFavorite ?: false
                MmkvManager.encodeProfileDirect(existingKey, JsonUtil.toJson(config))
                keyToProfile[existingKey] = config
            } else {
                val key = Utils.getUuid()
                MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(config))

                if (!serverList.contains(key)) {
                    serverList.add(0, key)
                    if (needSetSelected) {
                        MmkvManager.setSelectServer(key)
                        needSetSelected = false
                    }
                }
                keyToProfile[key] = config
            }
        }

        MmkvManager.encodeServerList(serverList, subid)
        return keyToProfile
    }

    private fun saveOldServerData(subid: String): Map<ProfileItem, Pair<Long, Boolean>> {
        val serverData = mutableMapOf<ProfileItem, Pair<Long, Boolean>>()
        val serverList = MmkvManager.decodeServerList(subid)
        
        serverList.forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null) {
                val aff = MmkvManager.decodeServerAffiliationInfo(guid)
                val delay = aff?.testDelayMillis ?: 0L
                if (delay > 0 || profile.isFavorite) {
                    serverData[profile] = Pair(delay, profile.isFavorite)
                }
            }
        }
        
        return serverData
    }

    private fun restoreOldServerData(keyToProfile: Map<String, ProfileItem>, oldServerData: Map<ProfileItem, Pair<Long, Boolean>>) {
        if (oldServerData.isEmpty()) return
        
        keyToProfile.forEach { (key, newProfile) ->
            val oldData = oldServerData[newProfile]
            
            if (oldData != null) {
                val (oldPing, isFavorite) = oldData
                if (oldPing > 0) {
                    MmkvManager.encodeServerTestDelayMillis(key, oldPing)
                }
                if (isFavorite) {
                    newProfile.isFavorite = true
                    MmkvManager.encodeServerConfig(key, newProfile)
                }
            }
        }
    }

    /**
     * Finds a matched profile key from the given key-profile map using multi-level matching.
     * Matching priority (from highest to lowest):
     * 1. Exact match: server + port + password
     * 2. Match by remarks (exact match)
     * 3. Match by server + port
     * 4. Match by server only
     *
     * @param keyToProfile Map of server keys to their ProfileItem
     * @param target Target profile to match
     * @return Matched key or null
     */
    private fun findMatchedProfileKey(keyToProfile: Map<String, ProfileItem>, target: ProfileItem?): String? {
        if (keyToProfile.isEmpty() || target == null) return null

        // Level 1: Match by remarks
        if (target.remarks.isNotBlank()) {
            keyToProfile.entries.firstOrNull { (_, saved) ->
                isSameText(saved.remarks, target.remarks)
            }?.key?.let { return it }
        }

        // Level 2: Exact match (server + port + password)
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort) &&
                    isSameText(saved.password, target.password)
        }?.key?.let { return it }

        // Level 3: Match by server + port
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort)
        }?.key?.let { return it }

        // Level 4: Match by server only
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server)
        }?.key?.let { return it }

        return null
    }

    /**
     * Case-insensitive trimmed string comparison.
     *
     * @param left First string
     * @param right Second string
     * @return True if both are non-empty and equal (case-insensitive, trimmed)
     */
    private fun isSameText(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        return synchronized(getSubscriptionLock(subid)) {
            if (server == null) {
                return@synchronized 0
            }
            if (server.contains("inbounds")
                && server.contains("outbounds")
                && server.contains("routing")
            ) {
                try {
                    val serverList: Array<Any> =
                        JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                    if (serverList.isNotEmpty()) {
                        if (!append) {
                            MmkvManager.removeServerViaSubid(subid)
                        }
                        var count = 0
                        for (srv in serverList.reversed()) {
                            val config = CustomFmt.parse(JsonUtil.toJson(srv)) ?: continue
                            config.subscriptionId = subid
                            config.description = generateDescription(config)
                            val key = MmkvManager.encodeServerConfig("", config)
                            MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(srv) ?: "")
                            count += 1
                        }
                        return@synchronized count
                    }
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
                }

                try {
                    val config = CustomFmt.parse(server) ?: return@synchronized 0
                    config.subscriptionId = subid
                    config.description = generateDescription(config)
                    if (!append) {
                        MmkvManager.removeServerViaSubid(subid)
                    }
                    val key = MmkvManager.encodeServerConfig("", config)
                    MmkvManager.encodeServerRaw(key, server)
                    return@synchronized 1
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
                }
                return@synchronized 0
            } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
                try {
                    val config = WireguardFmt.parseWireguardConfFile(server) ?: return@synchronized R.string.toast_incorrect_protocol
                    config.description = generateDescription(config)
                    if (!append) {
                        MmkvManager.removeServerViaSubid(subid)
                    }
                    val key = MmkvManager.encodeServerConfig("", config)
                    MmkvManager.encodeServerRaw(key, server)
                    return@synchronized 1
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
                }
                return@synchronized 0
            } else {
                return@synchronized 0
            }
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
                VmessFmt.parse(str)
            } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
                ShadowsocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
                SocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
                TrojanFmt.parse(str)
            } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
                VlessFmt.parse(str)
            } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
                WireguardFmt.parse(str)
            } else if (str.startsWith(EConfigType.HYSTERIA2.protocolScheme) || str.startsWith(HY2)) {
                Hysteria2Fmt.parse(str)
            } else {
                null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            return config
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            Log.i(AppConfig.TAG, url)
            val userAgent = it.subscription.userAgent

            val timeout = if (url.startsWith("https://key.zarazaex.xyz/sub")) 3000 else 15000

            var configText = try {
                val httpPort = SettingsManager.getHttpPort()
                HttpUtil.getUrlContentWithUserAgent(url, userAgent, timeout, httpPort)
            } catch (e: Exception) {
                Log.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
                ""
            }
            if (configText.isEmpty()) {
                configText = try {
                    HttpUtil.getUrlContentWithUserAgent(url, userAgent, timeout)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e)
                    ""
                }
            }
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                Log.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }
        return count
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        return "$server : ${port ?: ""}"
    }
}
