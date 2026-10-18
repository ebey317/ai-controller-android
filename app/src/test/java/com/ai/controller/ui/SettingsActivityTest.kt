package com.ai.controller.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.ai.controller.R
import com.ai.controller.models.ControllerInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Crew B — Robolectric activity test for [SettingsActivity].
 *
 * Verifies the mapping editor builds its full per-input list, wires the
 * RecyclerView, and opens the action-type picker when a row's Change button
 * is tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    private fun launch() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    @Test
    fun `creates with title and one row per controller input`() {
        val activity = launch()
        assertEquals(activity.getString(R.string.settings_title), activity.title.toString())

        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerButtonMappings)
        assertNotNull(recycler)
        assertNotNull(recycler.layoutManager)
        assertNotNull(recycler.adapter)
        assertEquals(ControllerInput.values().size, recycler.adapter!!.itemCount)
        assertEquals(16, recycler.adapter!!.itemCount)
    }

    @Test
    fun `change button on a row opens the action type dialog`() {
        val activity = launch()
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerButtonMappings)

        // Force layout so view holders are actually created and bound.
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)
        recycler.measure(width, height)
        recycler.layout(0, 0, 1080, 4000)

        val holder = recycler.findViewHolderForAdapterPosition(0) as ButtonMappingAdapter.ViewHolder
        assertEquals("BUTTON_A", holder.binding.textInputName.text.toString())
        holder.binding.buttonChangeMapping.performClick()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("tapping Change must open the action-type picker", dialog)
        assertEquals(
            activity.getString(R.string.mapping_dialog_title),
            dialog!!.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
        )
    }

    @Test
    fun `dialog lists every action type`() {
        val activity = launch()
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerButtonMappings)
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)
        recycler.measure(width, height)
        recycler.layout(0, 0, 1080, 4000)

        val holder = recycler.findViewHolderForAdapterPosition(0) as ButtonMappingAdapter.ViewHolder
        holder.binding.buttonChangeMapping.performClick()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val listView = dialog.listView
        assertNotNull(listView)
        assertEquals(
            com.ai.controller.models.ActionType.values().size,
            listView.adapter.count
        )
    }
}
