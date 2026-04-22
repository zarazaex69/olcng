package xyz.zarazaex.olc.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
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
    private var data: MutableList<ServersCache> = mutableListOf()
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
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        val parsedNewData = newData?.toList() ?: emptyList()

        if (data.isEmpty() || parsedNewData.isEmpty() || position >= 0) {
            data = parsedNewData.toMutableList()
            if (position >= 0 && position in data.indices) {
                notifyItemChanged(position)
            } else {
                notifyDataSetChanged()
            }
            return
        }

        val oldData = data
        val lm = recyclerView?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val firstVisible = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val isAtTop = firstVisible == 0 && (lm?.findViewByPosition(0)?.top ?: 0) >= 0
        val firstVisibleGuid = if (!isAtTop) oldData.getOrNull(firstVisible)?.guid else null

        val diffResult =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(
                        object : androidx.recyclerview.widget.DiffUtil.Callback() {
                            override fun getOldListSize() = oldData.size
                            override fun getNewListSize() = parsedNewData.size

                            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                                return oldData[oldPos].guid == parsedNewData[newPos].guid
                            }

                            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                                val oldProfile = oldData[oldPos].profile
                                val newProfile = parsedNewData[newPos].profile
                                return oldProfile == newProfile &&
                                        oldProfile.isFavorite == newProfile.isFavorite &&
                                        MmkvManager.decodeServerAffiliationInfo(
                                                        oldData[oldPos].guid
                                                )
                                                ?.testDelayMillis ==
                                                MmkvManager.decodeServerAffiliationInfo(
                                                                parsedNewData[newPos].guid
                                                        )
                                                        ?.testDelayMillis
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
                    val isFav = data[position].profile.isFavorite
                    animateFavorite(holder.itemMainBinding.ivFavorite, isFav)
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun animateFavorite(view: android.widget.ImageView, isFavorite: Boolean) {
        view.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(150)
                .withEndAction {
                    view.setImageResource(
                            if (isFavorite) R.drawable.ic_star_filled
                            else R.drawable.ic_star_empty
                    )
                    view.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
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
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)

            // TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(
                        ContextCompat.getColor(context, R.color.colorPingRed)
                )
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(
                        ContextCompat.getColor(context, R.color.colorPing)
                )
            }

            // layoutIndicator
            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.color.colorIndicator)
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            // subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility =
                    if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            val isFav = profile.isFavorite
            holder.itemMainBinding.ivFavorite.setImageResource(
                    if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_empty
            )

            holder.itemMainBinding.ivFavorite.setOnClickListener {
                profile.isFavorite = !profile.isFavorite
                MmkvManager.encodeServerConfig(guid, profile)
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
        return AngConfigManager.generateDescription(profile)
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

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
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
