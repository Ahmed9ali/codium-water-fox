package com.duckduckgo.app.tabs.ui

import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class TabGroupDragDetector(private val isGroupable: (Int) -> Boolean) {

    var target: RecyclerView.ViewHolder? = null
        private set

    fun update(rv: RecyclerView, dragged: RecyclerView.ViewHolder, dX: Float, dY: Float) {
        val v = dragged.itemView
        val cx = v.left + v.width / 2f + dX
        val cy = v.top + v.height / 2f + dY
        var found: RecyclerView.ViewHolder? = null
        if (isGroupable(dragged.bindingAdapterPosition)) {
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                if (child === v) continue
                val holder = rv.getChildViewHolder(child)
                val inZone = abs(cx - (child.x + child.width / 2f)) < child.width * ZONE &&
                    abs(cy - (child.y + child.height / 2f)) < child.height * ZONE
                if (inZone && isGroupable(holder.bindingAdapterPosition)) {
                    found = holder
                    break
                }
            }
        }
        if (found !== target) {
            target?.itemView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            found?.itemView?.animate()?.scaleX(HIGHLIGHT)?.scaleY(HIGHLIGHT)?.setDuration(120)?.start()
            target = found
        }
    }

    fun finish(): RecyclerView.ViewHolder? {
        val t = target
        t?.itemView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        target = null
        return t
    }

    private companion object {
        const val ZONE = 0.25f
        const val HIGHLIGHT = 1.06f
    }
}