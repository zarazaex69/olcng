package xyz.zarazaex.olc.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.util.Collections
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.contracts.MainAdapterListener
import xyz.zarazaex.olc.databinding.ItemRecyclerFooterBinding
import xyz.zarazaex.olc.databinding.ItemRecyclerMainBinding
import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.dto.ServersCache
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.helper.ItemTouchHelperAdapter
import xyz.zarazaex.olc.helper.ItemTouchHelperViewHolder
import xyz.zarazaex.olc.viewmodel.MainViewModel

class MainRecyclerAdapter(
        private val mainViewModel: MainViewModel,
        private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val PAYLOAD_FAVORITE = "PAYLOAD_FAVORITE"
    }

    private val doubleColumnDisplay =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private val showCopyButton =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_COPY_BUTTON, false)
    private val showServerIp =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SERVER_IP, false)
    private var data: MutableList<ServersCache> = mutableListOf()
    private var minReachablePing: Long? = null
    private var maxReachablePing: Long? = null
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
        (rv.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.apply {
            moveDuration = 400
            removeDuration = 300
            addDuration = 300
        }
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: List<ServersCache>) {
        val oldData = data
        val parsedNewData = newData

        if (oldData.isEmpty() || parsedNewData.isEmpty()) {
            data = parsedNewData.toMutableList()
            recomputePingRange()
            notifyDataSetChanged()
            return
        }

        val lm = recyclerView?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val firstVisible = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val isAtTop = firstVisible == 0 && (lm?.findViewByPosition(0)?.top ?: 0) >= 0
        val firstVisibleGuid = if (!isAtTop) oldData.getOrNull(firstVisible)?.guid else null

        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size
                override fun getNewListSize() = parsedNewData.size

                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    oldData[oldPos].guid == parsedNewData[newPos].guid

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val old = oldData[oldPos]
                    val new = parsedNewData[newPos]
                    return old.profile == new.profile &&
                            old.profile.isFavorite == new.profile.isFavorite &&
                            old.isSelected == new.isSelected &&
                            old.testDelayMillis == new.testDelayMillis
                }

                override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                    if (oldData[oldPos].profile.isFavorite != parsedNewData[newPos].profile.isFavorite) {
                        return PAYLOAD_FAVORITE
                    }
                    return super.getChangePayload(oldPos, newPos)
                }
            },
            true
        )

        data = parsedNewData.toMutableList()
        recomputePingRange()
        diffResult.dispatchUpdatesTo(this)

        if (isAtTop) {
            lm?.scrollToPositionWithOffset(0, 0)
        } else if (firstVisibleGuid != null) {
            val newPos = parsedNewData.indexOfFirst { it.guid == firstVisibleGuid }
            if (newPos >= 0) lm?.scrollToPosition(newPos)
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is MainViewHolder) {
            for (payload in payloads) {
                if (payload == PAYLOAD_FAVORITE) {
                    val item = data.getOrNull(holder.bindingAdapterPosition) ?: data.getOrNull(position) ?: continue
                    val isFav = item.profile.isFavorite
                    // Set correct icon immediately, then animate scale bounce
                    holder.itemMainBinding.ivFavorite.setImageResource(
                        if (isFav) R.drawable.ic_star_filled else R.drawable.kid_star_outline_24
                    )
                    animateFavorite(holder.itemMainBinding.ivFavorite)
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun animateFavorite(view: android.widget.ImageView) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.4f)
            .scaleY(1.4f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            // Name address
            holder.itemMainBinding.tvName.text = profile.remarks
            val addressText = getAddress(profile)
            holder.itemMainBinding.tvStatistics.text = addressText
            holder.itemMainBinding.tvStatistics.visibility =
                    if (addressText.isEmpty()) View.GONE else View.VISIBLE

            // TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            holder.itemMainBinding.tvTestResult.setTextColor(
                    getPingColor(context, aff?.testDelayMillis)
            )
            (holder.itemMainBinding.tvTestResult.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart =
                    if (addressText.isEmpty()) 0 else 6.dpToPx(context)

            val isSelected = data[position].isSelected
            holder.itemMainBinding.cardContainer.apply {
                val selectedColor = MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    Color.TRANSPARENT
                )

                setCardBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)
                strokeWidth = 0
                strokeColor = Color.TRANSPARENT
            }

            // subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility =
                    if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            val isFav = profile.isFavorite
            holder.itemMainBinding.ivFavorite.setImageResource(
                    if (isFav) R.drawable.ic_star_filled else R.drawable.kid_star_outline_24
            )

            holder.itemMainBinding.ivFavorite.setOnClickListener {
                profile.isFavorite = !profile.isFavorite
                MmkvManager.encodeServerConfig(guid, profile)
                holder.itemMainBinding.ivFavorite.setImageResource(
                    if (profile.isFavorite) R.drawable.ic_star_filled else R.drawable.kid_star_outline_24
                )
                animateFavorite(holder.itemMainBinding.ivFavorite)
                mainViewModel.reloadServerList()
            }

            holder.itemMainBinding.ivCopy.visibility = if (showCopyButton) View.VISIBLE else View.GONE
            holder.itemMainBinding.ivCopy.setOnClickListener {
                adapterListener?.onCopyToClipboard(guid)
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
    }

    /**
     * Gets the server address information Hides part of IP or domain information for privacy
     * protection
     * @param profile The server configuration
     * @return Formatted address string
     */
    private fun getAddress(profile: ProfileItem): String {
        if (!showServerIp) {
            return ""
        }
        return AngConfigManager.generateDescription(profile)
    }

    private fun getPingColor(context: android.content.Context, delayMillis: Long?): Int {
        val delay = delayMillis ?: return MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(context, R.color.colorPing)
        )
        if (delay == 0L) {
            return MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(context, R.color.colorPing)
            )
        }
        return when {
            delay < 0L -> ContextCompat.getColor(context, R.color.colorPingRed)
            minReachablePing == null || maxReachablePing == null -> ContextCompat.getColor(context, R.color.colorPingGood)
            minReachablePing == maxReachablePing -> ContextCompat.getColor(context, R.color.colorPingGood)
            else -> {
                val min = minReachablePing ?: delay
                val max = maxReachablePing ?: delay
                val relative = ((delay - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
                when {
                    relative <= 0.33f -> ContextCompat.getColor(context, R.color.colorPingGood)
                    relative <= 0.66f -> ContextCompat.getColor(context, R.color.colorPingMedium)
                    else -> ContextCompat.getColor(context, R.color.colorPingRed)
                }
            }
        }
    }

    private fun recomputePingRange() {
        val delays = data.mapNotNull { item ->
            MmkvManager.decodeServerAffiliationInfo(item.guid)
                ?.testDelayMillis
                ?.takeIf { it > 0L }
        }
        minReachablePing = delays.minOrNull()
        maxReachablePing = delays.maxOrNull()
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
                if (mainViewModel.subscriptionId.isEmpty())
                        MmkvManager.decodeSubscription(profile.subscriptionId)
                                ?.remarks
                                ?.firstOrNull()
                else null
        return subRemarks?.toString() ?: ""
    }

    private fun Int.dpToPx(context: android.content.Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                    MainViewHolder(
                            ItemRecyclerMainBinding.inflate(
                                    LayoutInflater.from(parent.context),
                                    parent,
                                    false
                            )
                    )
            else ->
                    FooterViewHolder(
                            ItemRecyclerFooterBinding.inflate(
                                    LayoutInflater.from(parent.context),
                                    parent,
                                    false
                            )
                    )
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
            BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
            BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        // ViewModel swaps both serverList and _serversCache, then publishSnapshot triggers setData.
        // We optimistically swap local data + animate immediately for smooth drag UX.
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {}
}
