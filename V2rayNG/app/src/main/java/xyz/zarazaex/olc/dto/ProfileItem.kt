package xyz.zarazaex.olc.dto

import xyz.zarazaex.olc.AppConfig.LOOPBACK
import xyz.zarazaex.olc.AppConfig.PORT_SOCKS
import xyz.zarazaex.olc.AppConfig.TAG_BLOCKED
import xyz.zarazaex.olc.AppConfig.TAG_DIRECT
import xyz.zarazaex.olc.AppConfig.TAG_PROXY
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,
    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var echForceQuery: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,

    ) {
    companion object {
        fun create(configType: EConfigType): ProfileItem {
            return ProfileItem(configType = configType)
        }
    }

    fun getAllOutboundTags(): MutableList<String> {
        return mutableListOf(TAG_PROXY, TAG_DIRECT, TAG_BLOCKED)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "$LOOPBACK:$PORT_SOCKS"
        }
        return Utils.getIpv6Address(server) + ":" + serverPort
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        val obj = other as ProfileItem

        return (this.server == obj.server
                && this.serverPort == obj.serverPort
                && this.password == obj.password
                && this.method == obj.method
                && this.flow == obj.flow
                && this.username == obj.username

                && this.network == obj.network
                && this.headerType == obj.headerType
                && this.host == obj.host
                && this.path == obj.path
                && this.seed == obj.seed
                && this.quicSecurity == obj.quicSecurity
                && this.quicKey == obj.quicKey
                && this.mode == obj.mode
                && this.serviceName == obj.serviceName
                && this.authority == obj.authority
                && this.xhttpMode == obj.xhttpMode

                && this.security == obj.security
                && this.sni == obj.sni
                && this.alpn == obj.alpn
                && this.fingerPrint == obj.fingerPrint
                && this.publicKey == obj.publicKey
                && this.shortId == obj.shortId

                && this.secretKey == obj.secretKey
                && this.localAddress == obj.localAddress
                && this.reserved == obj.reserved
                && this.mtu == obj.mtu

                && this.obfsPassword == obj.obfsPassword
                && this.portHopping == obj.portHopping
                && this.portHoppingInterval == obj.portHoppingInterval
                && this.pinnedCA256 == obj.pinnedCA256
                )
    }

    override fun hashCode(): Int {
        var result = server?.hashCode() ?: 0
        result = 31 * result + (serverPort?.hashCode() ?: 0)
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + (method?.hashCode() ?: 0)
        result = 31 * result + (flow?.hashCode() ?: 0)
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (network?.hashCode() ?: 0)
        result = 31 * result + (headerType?.hashCode() ?: 0)
        result = 31 * result + (host?.hashCode() ?: 0)
        result = 31 * result + (path?.hashCode() ?: 0)
        result = 31 * result + (seed?.hashCode() ?: 0)
        result = 31 * result + (quicSecurity?.hashCode() ?: 0)
        result = 31 * result + (quicKey?.hashCode() ?: 0)
        result = 31 * result + (mode?.hashCode() ?: 0)
        result = 31 * result + (serviceName?.hashCode() ?: 0)
        result = 31 * result + (authority?.hashCode() ?: 0)
        result = 31 * result + (xhttpMode?.hashCode() ?: 0)
        result = 31 * result + (security?.hashCode() ?: 0)
        result = 31 * result + (sni?.hashCode() ?: 0)
        result = 31 * result + (alpn?.hashCode() ?: 0)
        result = 31 * result + (fingerPrint?.hashCode() ?: 0)
        result = 31 * result + (publicKey?.hashCode() ?: 0)
        result = 31 * result + (shortId?.hashCode() ?: 0)
        result = 31 * result + (secretKey?.hashCode() ?: 0)
        result = 31 * result + (localAddress?.hashCode() ?: 0)
        result = 31 * result + (reserved?.hashCode() ?: 0)
        result = 31 * result + (mtu ?: 0)
        result = 31 * result + (obfsPassword?.hashCode() ?: 0)
        result = 31 * result + (portHopping?.hashCode() ?: 0)
        result = 31 * result + (portHoppingInterval?.hashCode() ?: 0)
        result = 31 * result + (pinnedCA256?.hashCode() ?: 0)
        return result
    }
}