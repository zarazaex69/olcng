package xyz.zarazaex.olc.contracts

import xyz.zarazaex.olc.dto.ProfileItem

interface MainAdapterListener :BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean)

    fun onCopyToClipboard(guid: String)

}