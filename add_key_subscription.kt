import com.google.gson.Gson
import java.io.File
import java.util.UUID

data class SubscriptionItem(
    val remarks: String,
    val url: String,
    val enabled: Boolean,
    val addedTime: Long,
    val lastUpdated: Long,
    val autoUpdate: Boolean,
    val filter: String,
    val allowInsecureUrl: Boolean,
    val userAgent: String
)

fun main() {
    val subFile = File("V2rayNG/app/src/main/assets/mmkv/SUB")
    val guid = UUID.randomUUID().toString().replace("-", "")
    
    val subscription = SubscriptionItem(
        remarks = "KEY",
        url = "https://key.zarazaex.xyz/sub",
        enabled = true,
        addedTime = System.currentTimeMillis(),
        lastUpdated = -1,
        autoUpdate = true,
        filter = "",
        allowInsecureUrl = false,
        userAgent = ""
    )
    
    val json = Gson().toJson(subscription)
    val guidBytes = guid.toByteArray(Charsets.UTF_8)
    val jsonBytes = json.toByteArray(Charsets.UTF_8)
    
    subFile.appendBytes(byteArrayOf(
        0x00, 0x00, 0x00, guidBytes.size.toByte()
    ) + guidBytes + byteArrayOf(
        0x00, 0x00, 0x00, jsonBytes.size.toByte()
    ) + jsonBytes)
    
    println("Добавлена подписка KEY с GUID: $guid")
}
