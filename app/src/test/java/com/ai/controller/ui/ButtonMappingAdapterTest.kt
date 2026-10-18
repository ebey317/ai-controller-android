package com.ai.controller.ui

import android.widget.LinearLayout
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.SwipeDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Crew B — Robolectric tests for [ButtonMappingAdapter].
 *
 * The adapter is exercised directly: item count, view binding (input name +
 * action summary), the change-button delegation, and updateAction().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ButtonMappingAdapterTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun rows() = mutableListOf(
        ControllerInput.BUTTON_A to ButtonAction.tap("Click"),
        ControllerInput.BUTTON_B to ButtonAction.back("Bksp"),
        ControllerInput.BUTTON_X to ButtonAction(ActionType.SWIPE, swipeDirection = SwipeDirection.UP)
    )

    private fun bind(adapter: ButtonMappingAdapter, position: Int): ButtonMappingAdapter.ViewHolder {
        val holder = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(holder, position)
        return holder
    }

    @Test
    fun `item count matches row list`() {
        assertEquals(3, ButtonMappingAdapter(rows()) { _, _ -> }.itemCount)
        assertEquals(0, ButtonMappingAdapter(mutableListOf()) { _, _ -> }.itemCount)
    }

    @Test
    fun `binding sets input name and plain action summary`() {
        val holder = bind(ButtonMappingAdapter(rows()) { _, _ -> }, 0)
        assertEquals("BUTTON_A", holder.binding.textInputName.text.toString())
        assertEquals("TAP", holder.binding.textActionSummary.text.toString())
    }

    @Test
    fun `binding appends swipe direction to the summary`() {
        val holder = bind(ButtonMappingAdapter(rows()) { _, _ -> }, 2)
        assertEquals("BUTTON_X", holder.binding.textInputName.text.toString())
        assertEquals("SWIPE (UP)", holder.binding.textActionSummary.text.toString())
    }

    @Test
    fun `change button delegates to the edit listener with row data`() {
        var clickedInput: ControllerInput? = null
        var clickedAction: ButtonAction? = null
        val adapter = ButtonMappingAdapter(rows()) { input, action ->
            clickedInput = input
            clickedAction = action
        }
        val holder = bind(adapter, 1)
        holder.binding.buttonChangeMapping.performClick()
        assertEquals(ControllerInput.BUTTON_B, clickedInput)
        assertEquals(ButtonAction.back("Bksp"), clickedAction)
    }

    @Test
    fun `updateAction replaces the row and rebinds the new summary`() {
        val adapter = ButtonMappingAdapter(rows()) { _, _ -> }
        val holder = bind(adapter, 0)
        assertEquals("TAP", holder.binding.textActionSummary.text.toString())

        adapter.updateAction(ControllerInput.BUTTON_A, ButtonAction.back("Back!"))

        val rebound = bind(adapter, 0)
        assertEquals("BACK", rebound.binding.textActionSummary.text.toString())
        assertEquals("BUTTON_A", rebound.binding.textInputName.text.toString())
    }

    @Test
    fun `updateAction with unknown input is a safe no-op`() {
        val adapter = ButtonMappingAdapter(rows()) { _, _ -> }
        adapter.updateAction(ControllerInput.DPAD_UP, ButtonAction.tap())
        assertEquals(3, adapter.itemCount)
        val holder = bind(adapter, 0)
        assertEquals("TAP", holder.binding.textActionSummary.text.toString())
    }
}
