package xyz.zarazaex.olc.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.databinding.ActivityMainBinding
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.enums.PermissionType
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.extension.toastError
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsChangeManager
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.UpdateCheckerManager
import xyz.zarazaex.olc.handler.V2RayServiceManager
import xyz.zarazaex.olc.util.MessageUtil
import xyz.zarazaex.olc.util.Utils
import xyz.zarazaex.olc.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {ActivityMainBinding.inflate(layoutInflater)}
    private var isLiteTesting = false
    private var easterEggClickCount = 0
    private var isEasterEggActive = false

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    @Volatile private var isFabOperationInProgress = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        findViewById<android.widget.TextView>(R.id.drawer_settings)?.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<android.widget.TextView>(R.id.drawer_per_app)?.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<android.widget.TextView>(R.id.drawer_check_update)?.setOnClickListener {
            startActivity(Intent(this, CheckUpdateActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        fun removeUnderlines(textView: android.widget.TextView?) {
            if (textView == null) return
            textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            val text = textView.text
            if (text is android.text.Spanned) {
                val spannable = android.text.SpannableStringBuilder(text)
                val spans = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
                for (span in spans) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    val flags = spannable.getSpanFlags(span)
                    val url = span.url
                    spannable.removeSpan(span)
                    spannable.setSpan(object : android.text.style.URLSpan(url) {
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }, start, end, flags)
                }
                textView.text = spannable
            }
        }
        removeUnderlines(findViewById(R.id.tv_forked))
        removeUnderlines(findViewById(R.id.tv_developed))
        
        findViewById<android.widget.TextView>(R.id.tv_developed)?.setOnClickListener {
            easterEggClickCount++
            if (easterEggClickCount >= 16) {
                activateEasterEgg()
            }
        }
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnSummaryLite.setOnClickListener { handleLiteAction() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
        importAllSubsOnStartup()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }

        checkForUpdatesOnStartup()
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }

        mainViewModel.liteTestFinished.observe(this) { finished ->
            if (finished && isLiteTesting) {
                isLiteTesting = false
                mainViewModel.sortByTestResults()
                mainViewModel.reloadServerList()

                val firstReachable = mainViewModel.serversCache.firstOrNull { cache ->
                    (MmkvManager.decodeServerAffiliationInfo(cache.guid)?.testDelayMillis ?: 0L) > 0L
                }
                if (firstReachable != null) {
                    MmkvManager.setSelectServer(firstReachable.guid)
                    showStatus("Подключаемся к быстрейшему серверу")
                    startV2RayWithPermission()
                } else {
                    showStatus("Нет доступных серверов!")
                }
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        if (isFabOperationInProgress) {
            return
        }
        isFabOperationInProgress = true

        val isRunning = mainViewModel.isRunning.value == true

        applyRunningState(isLoading = true, isRunning = false)

        lifecycleScope.launch {
            try {
                if (isRunning) {
                    Log.d(AppConfig.TAG, "FAB: stopping service")
                    V2RayServiceManager.stopVService(this@MainActivity)
                } else {
                    Log.d(AppConfig.TAG, "FAB: starting service")
                    startV2RayWithPermission()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "FAB: error", e)
                applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
            } finally {
                isFabOperationInProgress = false
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun handleLiteAction() {
        if (isFabOperationInProgress) {
            return
        }
        isFabOperationInProgress = true

        lifecycleScope.launch {
            try {
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }

                showStatus("Обновление профилей...")
                showLoading()
                isLiteTesting = true

                launch(Dispatchers.IO) {
                    val result = mainViewModel.updateConfigViaSubAll()
                    delay(500L)
                    launch(Dispatchers.Main) {
                        if (result.configCount > 0) {
                            mainViewModel.reloadServerList()
                            showStatus("Обновлено ${result.configCount} профилей. Запуск теста...")
                        } else {
                            showStatus("Запуск теста...")
                        }
                        hideLoading()

                        delay(500L)
                        showStatus("Выполняется замер задержки. Ожидаем завершения...")
                        mainViewModel.testAllRealPing()
                    }
                }
                delay(1500)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error in handleLiteAction", e)
            } finally {
                isFabOperationInProgress = false
            }
        }
    }

    private fun startV2RayWithPermission() {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            showStatus(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (isFabOperationInProgress) {
            return
        }
        isFabOperationInProgress = true

        lifecycleScope.launch {
            try {
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }
                startV2Ray()
                delay(1000)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error in restartV2Ray", e)
            } finally {
                isFabOperationInProgress = false
            }
        }
    }

    private var statusResetJob: kotlinx.coroutines.Job? = null

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    /** Show a temporary message in the status bar, then revert to connection state */
    private fun showStatus(message: String) {
        statusResetJob?.cancel()
        binding.tvTestState.text = message
        statusResetJob = lifecycleScope.launch {
            delay(3000)
            val isRunning = mainViewModel.isRunning.value == true
            binding.tvTestState.text = getString(
                if (isRunning) R.string.connection_connected
                else R.string.connection_not_connected
            )
        }
    }

    private fun showStatus(resId: Int) = showStatus(getString(resId))

    private  fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.btnSummaryLite.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.btnSummaryLite.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onResume() {
        super.onResume()
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {


        R.id.real_ping_all -> {
            showStatus(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            showStatus(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> showStatus(getString(R.string.toast_failure))
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus(getString(R.string.toast_failure))
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    private fun importAllSubsOnStartup() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    showStatus(getString(R.string.title_update_config_count, result.configCount))
                }
                hideLoading()
            }
        }
    }
    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    showStatus(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    showStatus(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    showStatus(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    showStatus(getString(R.string.title_export_config_count, ret))
                else
                    showStatus(getString(R.string.toast_failure))
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        showStatus(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        showStatus(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            showStatus(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            showStatus(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.check_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
    
    private fun checkForUpdatesOnStartup() {
        showStatus("Проверка обновлений...")
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(true)
                if (result.hasUpdate) {
                    showStatus("Доступно обновление ${result.latestVersion}")
                    showUpdateAvailableDialog(result)
                } else {
                    showStatus("Обновлений нет")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates on startup: ${e.message}")
            }
        }
    }
    
    private fun showUpdateAvailableDialog(result: xyz.zarazaex.olc.dto.CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let {
                    Utils.openUri(this, it)
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
    
    private fun activateEasterEgg() {
        if (isEasterEggActive) return
        isEasterEggActive = true
        
        lifecycleScope.launch {
            val colors = listOf(
                0xFFFF0000.toInt(),
                0xFFFF7F00.toInt(),
                0xFFFFFF00.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFF4B0082.toInt(),
                0xFF9400D3.toInt()
            )
            
            var colorIndex = 0
            while (isEasterEggActive) {
                binding.toolbar.setBackgroundColor(colors[colorIndex])
                binding.tabGroup.setBackgroundColor(colors[(colorIndex + 1) % colors.size])
                binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[(colorIndex + 2) % colors.size])
                binding.btnSummaryLite.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[(colorIndex + 3) % colors.size])
                
                colorIndex = (colorIndex + 1) % colors.size
                delay(200)
            }
        }
        
        replaceAllTextWith67(binding.root)
    }
    
    private fun replaceAllTextWith67(view: android.view.View) {
        when (view) {
            is android.widget.TextView -> {
                if (view.text.isNotEmpty()) {
                    view.text = "67"
                }
            }
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    replaceAllTextWith67(view.getChildAt(i))
                }
            }
        }
    }
}
