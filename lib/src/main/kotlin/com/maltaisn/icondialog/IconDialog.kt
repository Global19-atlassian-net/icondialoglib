/*
 * Copyright 2019 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.icondialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.Group
import androidx.core.content.res.use
import androidx.core.os.ConfigurationCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import com.maltaisn.icondialog.IconDialogContract.HeaderItemView
import com.maltaisn.icondialog.IconDialogContract.IconItemView
import com.maltaisn.icondialog.data.Category
import com.maltaisn.icondialog.data.Icon
import com.maltaisn.icondialog.pack.IconPack
import java.util.*


class IconDialog : DialogFragment(), IconDialogContract.View {

    private var presenter: IconDialogContract.Presenter? = null

    /** The settings used for the dialog. */
    override lateinit var settings: IconDialogSettings

    override val iconPack: IconPack
        get() = callback.iconDialogIconPack

    /**
     * The selected icon IDs in the [iconPack].
     * Must be set before showing the dialog.
     */
    override var selectedIconIds: List<Int> = emptyList()

    override val locale: Locale
        get() = ConfigurationCompat.getLocales(requireContext().resources.configuration)[0]

    private lateinit var dialogView: View
    private lateinit var titleTxv: TextView
    private lateinit var headerDiv: View
    private lateinit var searchEdt: EditText
    private lateinit var searchClearBtn: ImageView
    private lateinit var searchGroup: Group
    private lateinit var noResultTxv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var footerGroup: Group
    private lateinit var selectBtn: Button
    private lateinit var clearBtn: Button

    private lateinit var listAdapter: IconAdapter
    private lateinit var listLayout: IconLayoutManager

    private lateinit var searchHandler: Handler
    private val searchCallback = Runnable {
        presenter?.onSearchQueryEntered(searchEdt.text.toString())
    }

    private var maxDialogWidth = 0
    private var maxDialogHeight = 0
    private var iconSize = 0
    private var iconColorNormal = Color.BLACK
    private var iconColorSelected = Color.BLACK

    @SuppressLint("InflateParams", "Recycle")
    override fun onCreateDialog(state: Bundle?): Dialog {
        // Wrap icon dialog theme to context
        val context = requireContext()
        val style = context.obtainStyledAttributes(intArrayOf(R.attr.icdStyle)).use {
            it.getResourceId(0, R.style.IcdStyle)
        }
        val contextWrapper = ContextThemeWrapper(context, style)
        val localInflater = LayoutInflater.from(contextWrapper)

        // Get style attributes values
        contextWrapper.obtainStyledAttributes(R.styleable.IconDialog).use {
            maxDialogWidth = it.getDimensionPixelSize(R.styleable.IconDialog_icdMaxWidth, -1)
            maxDialogHeight = it.getDimensionPixelSize(R.styleable.IconDialog_icdMaxHeight, -1)
            iconSize = it.getDimensionPixelSize(R.styleable.IconDialog_icdIconSize, -1)
            iconColorNormal = it.getColor(R.styleable.IconDialog_icdIconColor, 0)
            iconColorSelected = it.getColor(R.styleable.IconDialog_icdSelectedIconColor, 0)
        }

        searchHandler = Handler()

        // Create the dialog view
        dialogView = localInflater.inflate(R.layout.icd_dialog_icon, null, false)
        titleTxv = dialogView.findViewById(R.id.icd_txv_title)
        headerDiv = dialogView.findViewById(R.id.icd_div_header)
        searchEdt = dialogView.findViewById(R.id.icd_edt_search)
        searchClearBtn = dialogView.findViewById(R.id.icd_imv_clear_search)
        searchGroup = dialogView.findViewById(R.id.icd_group_search)
        noResultTxv = dialogView.findViewById(R.id.icd_txv_no_result)
        progressBar = dialogView.findViewById(R.id.icd_progress_bar)

        // Search
        searchEdt.addTextChangedListener {
            searchHandler.removeCallbacks(searchCallback)
            searchHandler.postDelayed(searchCallback, SEARCH_DELAY)
        }
        searchEdt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacks(searchCallback)
                presenter?.onSearchActionEvent(searchEdt.text.toString())
                true
            } else {
                false
            }
        }

        // Icon list
        val rcv: RecyclerView = dialogView.findViewById(R.id.icd_rcv_icon_list)
        listAdapter = IconAdapter()
        listLayout = IconLayoutManager(context, iconSize)
        listLayout.spanSizeLookup = object : SpanSizeLookup() {
            override fun getSpanSize(pos: Int): Int {
                if (pos !in 0 until listAdapter.itemCount) return 0
                return presenter?.getItemSpanCount(pos, listLayout.spanCount) ?: 0
            }
        }
        rcv.adapter = listAdapter
        rcv.layoutManager = listLayout

        // Footer
        footerGroup = dialogView.findViewById(R.id.icd_group_footer)
        selectBtn = dialogView.findViewById(R.id.icd_btn_select)
        val cancelBtn: Button = dialogView.findViewById(R.id.icd_btn_cancel)
        clearBtn = dialogView.findViewById(R.id.icd_btn_clear)
        selectBtn.setOnClickListener { presenter?.onSelectBtnClicked() }
        cancelBtn.setOnClickListener { presenter?.onCancelBtnClicked() }
        clearBtn.setOnClickListener { presenter?.onClearBtnClicked() }

        // Dialog
        val dialog = Dialog(contextWrapper)
        dialog.setOnShowListener {
            // Get maximum dialog dimensions
            val fgPadding = Rect()
            val window = dialog.window!!
            window.decorView.background.getPadding(fgPadding)
            val metrics = context.resources.displayMetrics
            var height = metrics.heightPixels - fgPadding.top - fgPadding.bottom
            var width = metrics.widthPixels - fgPadding.top - fgPadding.bottom

            // Set dialog's dimensions
            if (width > maxDialogWidth) width = maxDialogWidth
            if (height > maxDialogHeight) height = maxDialogHeight
            window.setLayout(width, height)

            // Set dialog's content
            dialogView.layoutParams = ViewGroup.LayoutParams(width, height)
            dialog.setContentView(dialogView)

            // Attach the presenter
            presenter = IconDialogPresenter()
            presenter?.attach(this, state)
        }

        if (state != null) {
            settings = state.getParcelable("settings")!!

            // Restore layout manager state, which isn't saved by recycler view.
            listLayout.onRestoreInstanceState(state.getParcelable("listLayoutState"))
        }

        return dialog
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)

        state.putParcelable("settings", settings)
        state.putParcelable("listLayoutState", listLayout.onSaveInstanceState())
        presenter?.saveState(state)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detach the presenter
        presenter?.detach()
        presenter = null
    }

    override fun onCancel(dialog: DialogInterface) {
        presenter?.onDialogCancelled()
    }

    override fun exit() {
        dismiss()
    }

    override fun hideKeyboard() {
        if (searchEdt.hasFocus()) {
            searchEdt.clearFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchEdt.windowToken, 0)
        }
    }

    override fun setCancelResult() {
        callback.onIconDialogCancelled()
    }

    override fun setSelectionResult(selected: List<Icon>) {
        callback.onIconDialogIconsSelected(this, selected)
    }

    override fun setTitleVisible(visible: Boolean) {
        titleTxv.isVisible = visible
    }

    override fun setSearchBarVisible(visible: Boolean) {
        searchGroup.isVisible = visible
    }

    override fun setClearSearchBtnVisible(visible: Boolean) {
        searchClearBtn.isVisible = visible
    }

    override fun setClearBtnVisible(visible: Boolean) {
        clearBtn.isVisible = visible
    }

    override fun setProgressBarVisible(visible: Boolean) {
        progressBar.isVisible = visible
    }

    override fun setNoResultLabelVisible(visible: Boolean) {
        noResultTxv.isVisible = visible
    }

    override fun setFooterVisible(visible: Boolean) {
        footerGroup.isVisible = visible
    }

    override fun removeLayoutPadding() {
        dialogView.setPadding(0, 0, 0, 0)
        headerDiv.isVisible = false
    }

    override fun scrollToItemPosition(pos: Int) {
        listLayout.scrollToPositionWithOffset(pos, iconSize)
    }

    override fun setSelectBtnEnabled(enabled: Boolean) {
        selectBtn.isEnabled = enabled
    }

    override fun notifyIconItemChanged(pos: Int) {
        listAdapter.notifyItemChanged(pos)
    }

    override fun notifyAllIconsChanged() {
        listAdapter.notifyDataSetChanged()
    }

    override fun showMaxSelectionMessage() {
        Toast.makeText(context, R.string.icd_max_sel_message, Toast.LENGTH_SHORT).show()
    }


    private val callback: Callback
        get() = (parentFragment as? Callback)
                ?: (targetFragment as? Callback)
                ?: (activity as? Callback)
                ?: error("Icon dialog must have a callback.")


    private inner class IconAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        inner class IconViewHolder(view: View) :
                RecyclerView.ViewHolder(view), IconItemView {
            private val iconImv = view as ImageView

            init {
                iconImv.setOnClickListener {
                    presenter?.onIconItemClicked(adapterPosition)
                }
            }

            override fun bindView(icon: Icon, selected: Boolean) {
                val hasIcon = icon.drawable != null
                if (hasIcon) {
                    iconImv.setImageDrawable(icon.drawable)
                } else {
                    iconImv.setImageResource(R.drawable.icd_ic_unavailable)
                }
                iconImv.alpha = if (hasIcon) 1.0f else 0.3f
                iconImv.setColorFilter(if (selected) iconColorSelected else iconColorNormal,
                        PorterDuff.Mode.SRC_IN)
            }
        }

        internal inner class HeaderViewHolder(view: View) :
                RecyclerView.ViewHolder(view), HeaderItemView {
            private val headerTxv: TextView = view.findViewById(R.id.icd_header_txv)

            override fun bindView(category: Category) {
                headerTxv.text = category.resolveName(requireContext())
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == IconDialogPresenter.ITEM_TYPE_ICON) {
                IconViewHolder(inflater.inflate(R.layout.icd_item_icon, parent, false))
            } else {
                HeaderViewHolder(inflater.inflate(R.layout.icd_item_header, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            when (holder) {
                is IconItemView -> presenter?.onBindIconItemView(pos, holder)
                is HeaderItemView -> presenter?.onBindHeaderItemView(pos, holder)
            }
        }

        override fun getItemCount() = presenter?.itemCount ?: 0

        override fun getItemId(pos: Int) = presenter?.getItemId(pos) ?: 0

        override fun getItemViewType(pos: Int) = presenter?.getItemType(pos) ?: 0
    }

    /**
     * Callback interface to be implemented by parent activity, parent fragment or
     * target fragment and used to communicate the results of the icon dialog.
     */
    interface Callback {
        /**
         * The icon pack to be displayed by the dialog.
         */
        val iconDialogIconPack: IconPack

        /**
         * Called when icons are selected and user confirms the selection.
         */
        fun onIconDialogIconsSelected(dialog: IconDialog, icons: List<Icon>)

        /**
         * Called when user dismissed the dialog by clicking outside or by clicking
         * on the Cancel button.
         */
        fun onIconDialogCancelled() = Unit
    }

    enum class SearchVisibility {
        NEVER,
        ALWAYS,
        IF_LANGUAGE_AVAILABLE,
    }

    enum class TitleVisibility {
        NEVER,
        ALWAYS,
        IF_SEARCH_HIDDEN,
    }

    enum class HeadersVisibility {
        HIDE,
        SHOW,
        STICKY
    }

    companion object {
        /** Time to wait to start search after user has stopped typing. */
        private const val SEARCH_DELAY = 300L

        /**
         * Create a new instance of the dialog with [settings].
         * More settings can be set with the returned dialog instance later.
         */
        @JvmStatic
        fun newInstance(settings: IconDialogSettings): IconDialog {
            val dialog = IconDialog()
            dialog.settings = settings
            return dialog
        }
    }

}