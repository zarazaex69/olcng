package xyz.zarazaex.olc.dto

import java.io.Serializable

data class PingResultItem(
    val guid: String,
    val delay: Long
) : Serializable

data class PingProgressUpdate(
    val results: ArrayList<PingResultItem>,
    val finished: Int,
    val total: Int
) : Serializable
