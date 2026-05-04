package xyz.zarazaex.olc.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import xyz.zarazaex.olc.databinding.ItemRecyclerBypassListBinding
import xyz.zarazaex.olc.dto.AppInfo
import xyz.zarazaex.olc.viewmodel.PerAppProxyViewModel

class PerAppProxyAdapter(
    val apps: MutableList<AppInfo>,
    val viewModel: PerAppProxyViewModel
) : RecyclerView.Adapter<PerAppProxyAdapter.BaseViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is AppViewHolder) {
            val appInfo = apps[position - 1]
            holder.bind(appInfo)
        }
    }

    override fun getItemCount() = apps.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val ctx = parent.context
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = View(ctx)
                view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
                BaseViewHolder(view)
            }
            else -> AppViewHolder(ItemRecyclerBypassListBinding.inflate(LayoutInflater.from(ctx), parent, false))
        }
    }

    override fun getItemViewType(position: Int) = if (position == 0) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class AppViewHolder(private val itemBypassBinding: ItemRecyclerBypassListBinding) :
        BaseViewHolder(itemBypassBinding.root), View.OnClickListener {
        private lateinit var appInfo: AppInfo

        fun bind(appInfo: AppInfo) {
            this.appInfo = appInfo
            itemBypassBinding.icon.setImageDrawable(appInfo.appIcon)
            itemBypassBinding.name.text = if (appInfo.isSystemApp) {
                "** ${appInfo.appName}"
            } else {
                appInfo.appName
            }
            itemBypassBinding.packageName.text = appInfo.packageName
            itemBypassBinding.checkBox.isChecked = viewModel.contains(appInfo.packageName)
            itemView.setOnClickListener(this)
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onClick(v: View?) {
            val packageName = appInfo.packageName
            viewModel.toggle(packageName)
            val isNowSelected = viewModel.contains(packageName)
            itemBypassBinding.checkBox.isChecked = isNowSelected

            // Move selected items to top, unselected back to their position
            val currentPos = apps.indexOf(appInfo)
            if (currentPos < 0) return

            if (isNowSelected) {
                // Find first non-selected item position (insert before it)
                val insertAt = apps.indexOfFirst { !viewModel.contains(it.packageName) }
                    .takeIf { it >= 0 } ?: 0
                if (currentPos != insertAt) {
                    apps.removeAt(currentPos)
                    apps.add(insertAt, appInfo)
                    notifyItemMoved(currentPos + 1, insertAt + 1) // +1 for header
                }
            } else {
                // Move to end of selected group
                val lastSelected = apps.indexOfLast { viewModel.contains(it.packageName) }
                val insertAt = if (lastSelected < 0) 0 else lastSelected + 1
                if (currentPos != insertAt) {
                    apps.removeAt(currentPos)
                    apps.add(insertAt, appInfo)
                    notifyItemMoved(currentPos + 1, insertAt + 1)
                }
            }
        }
    }
}
