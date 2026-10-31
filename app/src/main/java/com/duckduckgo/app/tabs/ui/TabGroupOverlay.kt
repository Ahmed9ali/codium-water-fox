package com.duckduckgo.app.tabs.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import java.io.File
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

fun Context.showTabGroupOverlay(
    group: TabGroupItem,
    onTabClick: (String) -> Unit,
    onNewTab: () -> Unit,
    onUpdate: (String, Int) -> Unit,
    onUngroup: () -> Unit,
    onRemoveTab: (String) -> Unit,
    activeTabId: String? = null,
    previewPath: (String, String) -> String = { _, _ -> "" },
) {
    val dp = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val color = TabGroupColors.color(this, group.colorIndex)
    val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)

    fun icon(text: String, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(px(14), px(8), px(14), px(8))
        setOnClickListener(onClick)
    }

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(16), px(8), px(8))
    }
    header.addView(icon("\u2190") { dialog.dismiss() })
    val dot = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(px(16), px(16)).apply { setMargins(px(4), 0, px(10), 0) }
    }
    header.addView(dot)
    header.addView(
        TextView(this).apply {
            text = group.name
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        },
    )
    header.addView(icon("+") { dialog.dismiss(); onNewTab() })
    header.addView(
        icon("\u22EE") { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "Rename / change color")
            popup.menu.add(0, 2, 1, "Ungroup")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { dialog.dismiss(); showNewTabGroupDialog(group.name, group.colorIndex) { n, c -> onUpdate(n, c) } }
                    2 -> { dialog.dismiss(); onUngroup() }
                }
                true
            }
            popup.show()
        },
    )

    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(8), px(8), px(8), px(24))
    }
    group.tabs.chunked(2).forEach { pair ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..1) {
            val tab = pair.getOrNull(i)
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, px(160), 1f).apply { setMargins(px(6), px(6), px(6), px(6)) }
                if (tab != null) {
                    background = GradientDrawable().apply {
                        cornerRadius = px(12).toFloat()
                        setColor(ColorUtils.setAlphaComponent(color, 90))
                    }
                    clipToOutline = true
                    val img = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    addView(img)
                    tab.tabPreviewFile?.let { f ->
                        Glide.with(context.applicationContext).load(File(previewPath(tab.tabId, f))).into(img)
                    }
                    addView(
                        TextView(context).apply {
                            text = tab.title?.takeIf { it.isNotBlank() } ?: tab.url ?: "New tab"
                            maxLines = 2
                            textSize = 13f
                            setTextColor(Color.WHITE)
                            setPadding(px(10), px(6), px(10), px(6))
                            setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, 140))
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM,
                            )
                        },
                    )
                    if (tab.tabId == activeTabId) {
                        foreground = GradientDrawable().apply {
                            cornerRadius = px(12).toFloat()
                            setStroke(px(3), Color.WHITE)
                            setColor(Color.TRANSPARENT)
                        }
                    }
                    setOnClickListener { dialog.dismiss(); onTabClick(tab.tabId) }
                    setOnLongClickListener { v ->
                        PopupMenu(context, v).apply {
                            menu.add("Remove from group")
                            setOnMenuItemClickListener { dialog.dismiss(); onRemoveTab(tab.tabId); true }
                        }.show()
                        true
                    }
                }
            }
            row.addView(card)
        }
        grid.addView(row)
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ColorUtils.blendARGB(0xFF1E1E22.toInt(), color, 0.18f))
        addView(header)
        addView(ScrollView(this@showTabGroupOverlay).apply { addView(grid) })
    }
    dialog.setContentView(root)
    dialog.show()
}