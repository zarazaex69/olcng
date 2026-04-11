package xyz.zarazaex.olc.service

import android.content.Context
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.handler.V2rayConfigManager
import xyz.zarazaex.olc.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val dispatcher = Executors.newFixedThreadPool(cpu * 16).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, result))
                } catch (e: Exception) {
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, -1L))
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "$left / $count")
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }

        var bestDelay = retFailure
        
        for (attempt in 0 until 2) {
            try {
                val delay = withTimeout(10000L) {
                    V2RayNativeManager.measureOutboundDelay(
                        configResult.content, 
                        SettingsManager.getDelayTestUrl()
                    )
                }
                
                if (delay > 0 && (bestDelay == retFailure || delay < bestDelay)) {
                    bestDelay = delay
                }
                
                if (bestDelay > 0) {
                    break
                }
            } catch (e: Exception) {
                if (attempt == 0) {
                    try {
                        val delay = withTimeout(10000L) {
                            V2RayNativeManager.measureOutboundDelay(
                                configResult.content, 
                                SettingsManager.getDelayTestUrl(true)
                            )
                        }
                        
                        if (delay > 0 && (bestDelay == retFailure || delay < bestDelay)) {
                            bestDelay = delay
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        
        return bestDelay
    }
}

