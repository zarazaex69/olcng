package xyz.zarazaex.olc.service

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.handler.V2rayConfigManager
import xyz.zarazaex.olc.util.JsonUtil
import xyz.zarazaex.olc.util.MessageUtil

/**
 * Worker that runs a batch of real-ping tests independently. Optimized to use Go-level concurrency
 * for improved performance.
 */
class RealPingWorkerService(
        private val context: Context,
        private val guids: List<String>,
        private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val scope =
            CoroutineScope(job + Dispatchers.Default + CoroutineName("RealPingBatchWorker"))

    private val totalCount = AtomicInteger(guids.size)
    private val finishedCount = AtomicInteger(0)

    private val delayTestUrl = SettingsManager.getDelayTestUrl()

    data class PingItem(val guid: String, val config: String)

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                // Prepare configurations for batch test and shuffle for better async feel
                val items =
                        guids.shuffled().mapNotNull { guid ->
                            try {
                                val configResult =
                                        V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                                if (configResult.status) {
                                    PingItem(guid, configResult.content)
                                } else {
                                    // Notify failure immediately for invalid configs
                                    reportResult(guid, -1L)
                                    null
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(AppConfig.TAG, "Failed to prepare config for $guid", e)
                                reportResult(guid, -1L)
                                null
                            }
                        }

                if (items.isNotEmpty()) {
                    val configsJson = JsonUtil.toJson(items)

                    V2RayNativeManager.measureOutboundDelayBatch(
                            configsJson,
                            delayTestUrl,
                            object : libv2ray.PingCallback {
                                override fun onResult(guid: String?, delay: Long) {
                                    if (guid != null) {
                                        reportResult(guid, delay)
                                    }
                                }
                            }
                    )
                }

                if (job.isActive) {
                    onFinish("0")
                }
            } catch (e: Exception) {
                if (job.isActive) {
                    onFinish("-1")
                }
            } finally {
                cancel()
            }
        }
    }

    private fun reportResult(guid: String, delay: Long) {
        if (!job.isActive) return

        // Launch in scope to unblock Go worker immediately
        scope.launch {
            val finished = finishedCount.incrementAndGet()
            val total = guids.size

            // Notify UI about the individual result
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, delay))

            // Throttle progress updates: every 10 items or the very last one
            if (finished % 10 == 0 || finished == total) {
                val left = total - finished
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "$left / $total")
            }
        }
    }

    fun cancel() {
        job.cancel()
    }
}
