package xyz.zarazaex.olc.dto

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val testDelayMillis: Long = 0L,
    val isSelected: Boolean = false
)