import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.util.UUID

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    val updateInterval: Int? = null,
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null
)

class MMKVWriter(private val filePath: String) {
    private val gson = Gson()
    
    fun writeEntry(key: String, json: String) {
        val file = RandomAccessFile(filePath, "rw")
        file.seek(file.length())
        
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        
        file.writeInt(keyBytes.size)
        file.write(keyBytes)
        file.writeInt(jsonBytes.size)
        file.write(jsonBytes)
        
        file.close()
    }
}

class SubscriptionManager(private val mmkvPath: String) {
    private val gson = Gson()
    private val writer = MMKVWriter(mmkvPath)

    fun addSubscription(remarks: String, url: String, autoUpdate: Boolean = true): String {
        val guid = UUID.randomUUID().toString().replace("-", "")
        
        val subItem = SubscriptionItem(
            remarks = remarks,
            url = url,
            enabled = true,
            addedTime = System.currentTimeMillis(),
            lastUpdated = -1,
            autoUpdate = autoUpdate,
            filter = "",
            allowInsecureUrl = false,
            userAgent = ""
        )
        
        val json = gson.toJson(subItem)
        writer.writeEntry(guid, json)
        
        return guid
    }
    
    fun updateSubscription(guid: String, url: String): Boolean {
        return try {
            println("Обновление подписки $guid...")
            val content = URL(url).readText()
            val lines = content.lines().filter { it.isNotBlank() }
            println("Загружено ${lines.size} конфигураций")
            
            val subItem = readSubscription(guid) ?: return false
            subItem.lastUpdated = System.currentTimeMillis()
            
            val json = gson.toJson(subItem)
            writer.writeEntry(guid, json)
            
            true
        } catch (e: Exception) {
            println("Ошибка обновления: ${e.message}")
            false
        }
    }
    
    private fun readSubscription(guid: String): SubscriptionItem? {
        val file = File(mmkvPath)
        if (!file.exists()) return null
        
        val data = file.readBytes()
        var pos = 0
        
        while (pos < data.size) {
            if (pos + 4 > data.size) break
            
            val keyLen = java.nio.ByteBuffer.wrap(data, pos, 4).int
            pos += 4
            if (pos + keyLen > data.size) break
            
            val key = String(data, pos, keyLen, Charsets.UTF_8)
            pos += keyLen
            
            if (pos + 4 > data.size) break
            val jsonLen = java.nio.ByteBuffer.wrap(data, pos, 4).int
            pos += 4
            if (pos + jsonLen > data.size) break
            
            val json = String(data, pos, jsonLen, Charsets.UTF_8)
            pos += jsonLen
            
            if (key == guid) {
                return gson.fromJson(json, SubscriptionItem::class.java)
            }
        }
        
        return null
    }
}

fun main() {
    val mmkvPath = "/home/zarazaex/Projects/olcng/V2rayNG/app/src/main/assets/mmkv/SUB"
    
    val manager = SubscriptionManager(mmkvPath)
    
    val guid1 = manager.addSubscription(
        remarks = "БЕЛЫЕ Z",
        url = "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt",
        autoUpdate = true
    )
    println("Добавлена подписка БЕЛЫЕ Z: $guid1")
    
    val guid2 = manager.addSubscription(
        remarks = "БЕЛЫЕ W",
        url = "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt",
        autoUpdate = true
    )
    println("Добавлена подписка БЕЛЫЕ W: $guid2")
    
    val guid3 = manager.addSubscription(
        remarks = "KEY",
        url = "https://key.zarazaex.xyz/sub",
        autoUpdate = true
    )
    println("Добавлена подписка KEY: $guid3")
    
    println("\nОбновление подписок...")
    manager.updateSubscription(guid1, "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt")
    manager.updateSubscription(guid2, "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt")
    manager.updateSubscription(guid3, "https://key.zarazaex.xyz/sub")
    
    println("\nПодписки успешно добавлены и обновлены в $mmkvPath")
}
