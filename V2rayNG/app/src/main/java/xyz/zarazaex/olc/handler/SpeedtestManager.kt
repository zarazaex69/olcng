package xyz.zarazaex.olc.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.dto.IPAPIInfo
import xyz.zarazaex.olc.util.HttpUtil
import xyz.zarazaex.olc.util.JsonUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    private val tcpTestingSockets = ArrayList<Socket?>()

    /**
     * Measures the TCP connection time to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    suspend fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int): Long {
        try {
            val socket = Socket()
            synchronized(this) {
                tcpTestingSockets.add(socket)
            }
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(url, port), 3000)
            val time = System.currentTimeMillis() - start
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "socketConnectTime IOException: $e")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        }
        return -1
    }

    /**
     * Closes all TCP sockets that are currently being tested.
     */
    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }

    /**
     * Tests the connection to a given URL and port.
     *
     * @param context The Context in which the test is running.
     * @param port The port to connect to.
     * @return A pair containing the elapsed time in milliseconds and the result message.
     */
    fun testConnection(context: Context, port: Int): Pair<Long, String> {
        var result: String
        var elapsed = -1L

        val testUrl = "https://icanhazip.com"
        val conn = HttpUtil.createProxyConnection(testUrl, port, 15000, 15000) ?: return Pair(elapsed, "")
        try {
            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            
            if (code != 200) {
                throw IOException(context.getString(R.string.connection_test_error_status_code, code))
            }
            
            val responseBody = conn.inputStream.bufferedReader().readText().trim()
            elapsed = SystemClock.elapsedRealtime() - start
            
            if (xyz.zarazaex.olc.util.Utils.isPureIpAddress(responseBody)) {
                result = context.getString(R.string.connection_test_available, elapsed)
            } else {
                throw IOException("Invalid IP response: $responseBody")
            }
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "Connection test IOException", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Connection test Exception", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } finally {
            conn.disconnect()
        }

        return Pair(elapsed, result)
    }

    fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val httpPort = SettingsManager.getHttpPort()
        val content = HttpUtil.getUrlContent(url, 5000, httpPort) ?: return null
        val ipInfo = JsonUtil.fromJson(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return "(${country ?: "unknown"}) ${ip ?: "unknown"}"
    }
}
