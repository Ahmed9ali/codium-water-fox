package com.duckduckgo.app.tabs.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

fun Context.showGroupDialogFor(dragged: TabSwitcherItem?, target: TabSwitcherItem?) {
    if (dragged == null || target == null) return
    showNewTabGroupDialog("2 tabs") { name, colorIndex ->
        Toast.makeText(this, "Group \"$name\" (color $colorIndex)", Toast.LENGTH_SHORT).show()
    }
}

fun Context.showNewTabGroupDialog(
    defaultName: String,
    onDone: (name: String, colorIndex: Int) -> Unit,
) {
    val ctx = this
    val dp = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    var selected = 0
    val circles = mutableListOf<View>()

    fun refresh() {
        circles.forEachIndexed { i, v ->
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(TabGroupColors.color(ctx, i))
            if (i == selected) d.setStroke(px(3), 0xFFFFFFFF.toInt())
            v.background = d
        }
    }

    fun row(range: IntRange): LinearLayout {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER
        for (i in range) {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(px(44), px(44))
            lp.setMargins(px(6), px(6), px(6), px(6))
            v.layoutParams = lp
            v.setOnClickListener {
                selected = i
                refresh()
            }
            circles.add(v)
            row.addView(v)
        }
        return row
    }

    val root = LinearLayout(ctx)
    root.orientation = LinearLayout.VERTICAL
    root.setPadding(px(24), px(8), px(24), 0)

    val input = EditText(ctx)
    input.setText(defaultName)
    input.setSelectAllOnFocus(true)
    input.inputType = InputType.TYPE_CLASS_TEXT
    input.maxLines = 1
    root.addView(input)
    root.addView(row(0..4))
    root.addView(row(5..8))
    refresh()

    AlertDialog.Builder(ctx)
        .setTitle("New tab group")
        .setView(root)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton("Done") { _, _ ->
            onDone(input.text.toString().ifBlank { defaultName }, selected)
        }
        .show()
}