package xyz.zarazaex.olc.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import xyz.zarazaex.olc.AngApplication
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.dto.GroupMapItem
import xyz.zarazaex.olc.dto.PingProgressUpdate
import xyz.zarazaex.olc.dto.ServersCache
import xyz.zarazaex.olc.dto.SubscriptionCache
import xyz.zarazaex.olc.dto.SubscriptionUpdateResult
import xyz.zarazaex.olc.dto.TestServiceMessage
import xyz.zarazaex.olc.extension.matchesPattern
import xyz.zarazaex.olc.extension.serializable
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.CountryDetector
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.SpeedtestManager
import xyz.zarazaex.olc.util.MessageUtil
import xyz.zarazaex.olc.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    /** ISO codes to show (empty = show all) */
    var countryFilter: Set<String> = MmkvManager.getCountryFilter()
        private set
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val liteTestFinished = MutableLiveData<Boolean>()
    val isTesting by lazy { MutableLiveData<Boolean>().also { it.value = false } }
    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        serverList = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else if (subscriptionId.startsWith("group_")) {
            val allSubs = MmkvManager.decodeSubscriptions()
            val groupSubs = when (subscriptionId) {
                "group_white" -> allSubs.filter { 
                    it.subscription.remarks.startsWith("БЕЛЫЕ", ignoreCase = true) || 
                    it.subscription.remarks.startsWith("WHITE", ignoreCase = true)
                }
                "group_black" -> allSubs.filter { 
                    it.subscription.remarks.startsWith("ЧЕРНЫЕ", ignoreCase = true) || 
                    it.subscription.remarks.startsWith("BLACK", ignoreCase = true)
                }
                else -> emptyList()
            }
            
            data class ServerWithDelay(val guid: String, val delay: Long, val isFav: Boolean)
            val allServers = mutableListOf<ServerWithDelay>()
            
            groupSubs.forEach { sub ->
                val subServers = MmkvManager.decodeServerList(sub.guid)
                subServers.forEach { guid ->
                    val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                    val isFav = MmkvManager.decodeServerConfig(guid)?.isFavorite ?: false
                    val sortKey = when {
                        delay > 0L -> delay
                        delay == 0L -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                    allServers.add(ServerWithDelay(guid, sortKey, isFav))
                }
            }
            
            allServers.sortWith(compareBy({ !it.isFav }, { it.delay }))
            allServers.map { it.guid }.toMutableList()
        } else {
            val list = MmkvManager.decodeServerList(subscriptionId)
            list.sortWith(compareBy { !(MmkvManager.decodeServerConfig(it)?.isFavorite ?: false) })
            list
        }

        updateCache()
        updateListAction.value = -1
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty() || subscriptionId.startsWith("group_")) {
            return
        }

        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null
        }
        val activeCountryFilter = countryFilter
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue

            // Country filter
            if (activeCountryFilter.isNotEmpty()) {
                val code = CountryDetector.getCountryCode(profile.remarks, profile.server)
                if (code !in activeCountryFilter) continue
            }

            if (kw.isEmpty()) {
                serversCache.add(ServersCache(guid, profile))
                continue
            }

            val remarks = profile.remarks
            val description = profile.description.orEmpty()
            val server = profile.server.orEmpty()
            val protocol = profile.configType.name
            if (remarks.matchesPattern(searchRegex, kw)
                || description.matchesPattern(searchRegex, kw)
                || server.matchesPattern(searchRegex, kw)
                || protocol.matchesPattern(searchRegex, kw)
            ) {
                serversCache.add(ServersCache(guid, profile))
            }
        }
    }

    /** Sets a new country filter and reloads list. Pass empty set to show all. */
    fun applyCountryFilter(codes: Set<String>) {
        countryFilter = codes
        MmkvManager.setCountryFilter(codes)
        reloadServerList()
    }

    /**
     * Returns all known countries from the current full server list (for showing in filter dialog).
     * Key = ISO code, Value = human-readable name + flag.
     */
    fun collectAllCountries(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var hasUnknown = false
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val code = CountryDetector.getCountryCode(profile.remarks, profile.server)
            if (code == CountryDetector.UNKNOWN) {
                hasUnknown = true
            } else {
                result[code] = "${CountryDetector.codeToFlag(code)} ${CountryDetector.codeToName(code)}"
            }
        }
        if (hasUnknown) {
            result[CountryDetector.UNKNOWN] = "🌐 Неизвестно"
        }
        return result.toSortedMap()
    }

    /** Trigger background geo-lookup for IPs not yet cached. */
    fun refreshCountryCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val ips = serverList.mapNotNull {
                MmkvManager.decodeServerConfig(it)?.server?.trim()
            }.distinct()
            CountryDetector.lookupAndCacheAll(ips)
            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else if (subscriptionId.startsWith("group_")) {
            val allSubs = MmkvManager.decodeSubscriptions()
            val groupSubs = when (subscriptionId) {
                "group_white" -> allSubs.filter {
                    it.subscription.remarks.startsWith("БЕЛЫЕ", ignoreCase = true) ||
                    it.subscription.remarks.startsWith("WHITE", ignoreCase = true)
                }
                "group_black" -> allSubs.filter {
                    it.subscription.remarks.startsWith("ЧЕРНЫЕ", ignoreCase = true) ||
                    it.subscription.remarks.startsWith("BLACK", ignoreCase = true)
                }
                else -> emptyList()
            }
            // Parallel fetch for group subs (sequential, called from IO context)
            return groupSubs.fold(SubscriptionUpdateResult()) { acc, sub ->
                acc + AngConfigManager.updateConfigViaSub(SubscriptionCache(sub.guid, sub.subscription))
            }
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList
            } else {
                serversCache.map { it.guid }.toList()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the TCP ping for all servers.
     */
    fun testAllTcping() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())

        val serversCopy = serversCache.toList()
        for (item in serversCopy) {
            item.profile.let { outbound ->
                val serverAddress = outbound.server
                val serverPort = outbound.serverPort
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                            updateListAction.value = getPosition(item.guid)
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancels all running ping tests.
     */
    fun cancelAllTests() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        isTesting.value = false
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )

        // Auto-deduplicate by IP before scanning so we don't waste time on dupes
        viewModelScope.launch(Dispatchers.IO) {
            val removed = removeDuplicateByIpAll()
            withContext(Dispatchers.Main) {
                if (removed > 0) {
                    reloadServerList()
                }
                MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())
                updateListAction.value = -1
                isTesting.value = true

                viewModelScope.launch(Dispatchers.Default) {
                    if (serversCache.isEmpty()) {
                        withContext(Dispatchers.Main) { reloadServerList() }
                    }
                    if (serversCache.isEmpty()) {
                        withContext(Dispatchers.Main) { isTesting.value = false }
                        return@launch
                    }

                    val actualSubId = if (subscriptionId.startsWith("group_")) "" else subscriptionId

                    MessageUtil.sendMsg2TestService(
                        getApplication(),
                        TestServiceMessage(
                            key = AppConfig.MSG_MEASURE_CONFIG,
                            subscriptionId = actualSubId,
                            serverGuids = if (keywordFilter.isNotEmpty() || subscriptionId.startsWith("group_")) {
                                serversCache.map { it.guid }
                            } else {
                                emptyList()
                            }
                        )
                    )
                }
            }
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        reloadServerList()
    }

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun getSubscriptions(context: Context): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptionId.isNotEmpty()
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        val groups = mutableListOf<GroupMapItem>()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)) {
            groups.add(
                GroupMapItem(
                    id = "",
                    remarks = context.getString(R.string.filter_config_all),
                    subIds = emptyList()
                )
            )
        }

        val whiteList = mutableListOf<String>()
        val blackList = mutableListOf<String>()
        val otherGroups = mutableListOf<GroupMapItem>()

        subscriptions.forEach { sub ->
            val remarks = sub.subscription.remarks
            when {
                remarks.startsWith("БЕЛЫЕ", ignoreCase = true) || 
                remarks.startsWith("WHITE", ignoreCase = true) -> {
                    whiteList.add(sub.guid)
                }
                remarks.startsWith("ЧЕРНЫЕ", ignoreCase = true) || 
                remarks.startsWith("BLACK", ignoreCase = true) -> {
                    blackList.add(sub.guid)
                }
                else -> {
                    otherGroups.add(
                        GroupMapItem(
                            id = sub.guid,
                            remarks = remarks,
                            subIds = listOf(sub.guid)
                        )
                    )
                }
            }
        }

        if (whiteList.isNotEmpty()) {
            groups.add(
                GroupMapItem(
                    id = "group_white",
                    remarks = "БЕЛЫЕ СПИСКИ",
                    subIds = whiteList
                )
            )
        }

        if (blackList.isNotEmpty()) {
            groups.add(
                GroupMapItem(
                    id = "group_black",
                    remarks = "ЧЕРНЫЕ СПИСКИ",
                    subIds = blackList
                )
            )
        }

        groups.addAll(otherGroups)

        return groups
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    /**
     * Removes duplicate servers.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serversCacheCopy = serversCache.toList().toMutableList()
        val deleteServer = mutableListOf<String>()
        serversCacheCopy.forEachIndexed { index, sc ->
            val profile = sc.profile
            serversCacheCopy.forEachIndexed { index2, sc2 ->
                if (index2 > index) {
                    val profile2 = sc2.profile
                    if (profile == profile2 && !deleteServer.contains(sc2.guid)) {
                        deleteServer.add(sc2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes servers with duplicate IP addresses (same `server` field),
     * keeping the one with the best ping result (or the first encountered if untested).
     * @return Number of removed servers.
     */
    fun removeDuplicateByIp(): Int {
        // Group all currently visible servers by their IP address
        val byIp = LinkedHashMap<String, MutableList<ServersCache>>()
        for (sc in serversCache) {
            val ip = sc.profile.server?.trim()?.lowercase() ?: continue
            byIp.getOrPut(ip) { mutableListOf() }.add(sc)
        }

        val toDelete = mutableListOf<String>()
        for ((_, group) in byIp) {
            if (group.size <= 1) continue
            val best = group.minWithOrNull(compareBy(
                { !it.profile.isFavorite },
                {
                    val d = MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L
                    when {
                        d > 0L -> d
                        d == 0L -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                }
            ))!!
            group.filter { it.guid != best.guid }.forEach { toDelete.add(it.guid) }
        }

        for (guid in toDelete) {
            MmkvManager.removeServer(guid)
        }
        return toDelete.size
    }

    /**
     * Removes duplicate servers by IP across ALL subscriptions (for use after sub update / before scan).
     * Per-subscription deduplication: within each sub keeps the best (favorite > lowest ping > first).
     */
    /**
     * Removes servers with duplicate IP addresses across ALL subscriptions globally.
     * Keeps the best one per IP (favorite > lowest ping > first encountered).
     * @return Number of removed servers.
     */
    fun removeDuplicateByIpAll(): Int {
        // Collect every server GUID across all subscriptions
        data class Entry(val guid: String, val ip: String, val isFav: Boolean)

        val allEntries = mutableListOf<Entry>()
        val allSubIds = MmkvManager.decodeSubsList().toMutableList()
        // Add the default (no-sub) slot if not already present
        if (!allSubIds.contains(AppConfig.DEFAULT_SUBSCRIPTION_ID)) {
            allSubIds.add(0, AppConfig.DEFAULT_SUBSCRIPTION_ID)
        }

        for (subId in allSubIds) {
            for (guid in MmkvManager.decodeServerList(subId)) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val ip = profile.server?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                allEntries.add(Entry(guid, ip, profile.isFavorite))
            }
        }

        // Group by IP globally
        val byIp = LinkedHashMap<String, MutableList<Entry>>()
        for (e in allEntries) {
            byIp.getOrPut(e.ip) { mutableListOf() }.add(e)
        }

        val toDelete = mutableListOf<String>()
        for ((_, group) in byIp) {
            if (group.size <= 1) continue
            val best = group.minWith(compareBy(
                { !it.isFav },
                {
                    val d = MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L
                    when {
                        d > 0L -> d
                        d == 0L -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                }
            ))
            group.filter { it.guid != best.guid }.forEach { toDelete.add(it.guid) }
        }

        for (guid in toDelete) {
            MmkvManager.removeServer(guid)
        }
        return toDelete.size
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    /**
     * Sorts serversCache in-place by test delay in real time (during a ping test).
     * Favorites always come first, then sorted ascending by delay (failed/untested go to bottom).
     */
    @Synchronized
    fun sortServersCacheInPlace() {
        serversCache.sortWith(compareBy(
            { !it.profile.isFavorite },
            {
                val delay = MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L
                when {
                    delay > 0L -> delay
                    delay == 0L -> Long.MAX_VALUE - 1  // untested
                    else -> Long.MAX_VALUE              // failed
                }
            }
        ))
    }

    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else if (subscriptionId.startsWith("group_")) {
            sortByTestResultsForGroup(subscriptionId)
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    private fun sortByTestResultsForGroup(groupId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long, var subId: String, var isFav: Boolean)

        val allSubs = MmkvManager.decodeSubscriptions()
        val groupSubs = when (groupId) {
            "group_white" -> allSubs.filter { 
                it.subscription.remarks.startsWith("БЕЛЫЕ", ignoreCase = true) || 
                it.subscription.remarks.startsWith("WHITE", ignoreCase = true)
            }
            "group_black" -> allSubs.filter { 
                it.subscription.remarks.startsWith("ЧЕРНЫЕ", ignoreCase = true) || 
                it.subscription.remarks.startsWith("BLACK", ignoreCase = true)
            }
            else -> emptyList()
        }

        val allServerDelays = mutableListOf<ServerDelay>()
        
        groupSubs.forEach { sub ->
            val serverList = MmkvManager.decodeServerList(sub.guid)
            serverList.forEach { guid ->
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                val isFav = MmkvManager.decodeServerConfig(guid)?.isFavorite ?: false
                val sortKey = when {
                    delay > 0L -> delay
                    delay == 0L -> Long.MAX_VALUE - 1
                    else -> Long.MAX_VALUE
                }
                allServerDelays.add(ServerDelay(guid, sortKey, sub.guid, isFav))
            }
        }
        
        allServerDelays.sortWith(compareBy({ !it.isFav }, { it.testDelayMillis }))
        
        val serversBySubId = allServerDelays.groupBy { it.subId }
        serversBySubId.forEach { (subId, servers) ->
            val sortedList = servers.map { it.guid }.toMutableList()
            MmkvManager.encodeServerList(sortedList, subId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long, var isFav: Boolean)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            val isFav = MmkvManager.decodeServerConfig(key)?.isFavorite ?: false
            val sortKey = when {
                delay > 0L -> delay
                delay == 0L -> Long.MAX_VALUE - 1
                else -> Long.MAX_VALUE
            }
            serverDelays.add(ServerDelay(key, sortKey, isFav))
        }
        serverDelays.sortWith(compareBy({ !it.isFav }, { it.testDelayMillis }))

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        val subId = config?.subscriptionId ?: return null
        
        val subscription = MmkvManager.decodeSubscription(subId)
        val remarks = subscription?.remarks ?: return subId
        
        return when {
            remarks.startsWith("БЕЛЫЕ", ignoreCase = true) || 
            remarks.startsWith("WHITE", ignoreCase = true) -> "group_white"
            remarks.startsWith("ЧЕРНЫЕ", ignoreCase = true) || 
            remarks.startsWith("BLACK", ignoreCase = true) -> "group_black"
            else -> subId
        }
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
                isTesting.value = false
                liteTestFinished.value = true
                liteTestFinished.value = false
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.serializable<Pair<String, Long>>("content") ?: return
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second)
                    sortServersCacheInPlace()
                    updateListAction.value = -1
                }

                AppConfig.MSG_MEASURE_CONFIG_BATCH -> {
                    val update = intent.serializable<PingProgressUpdate>("content") ?: return
                    update.results.forEach { result ->
                        MmkvManager.encodeServerTestDelayMillis(result.guid, result.delay)
                    }
                    sortServersCacheInPlace()
                    updateListAction.value = -1
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(R.string.connection_runing_task_left, content)
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    } else {
                        // cancelled or finished with non-zero count still in queue — mark as not testing
                        isTesting.value = false
                    }
                }
            }
        }
    }
}
