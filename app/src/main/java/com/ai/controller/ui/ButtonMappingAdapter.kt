package com.ai.controller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ai.controller.databinding.ItemButtonMappingBinding
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput

/**
 * Renders one row per [ControllerInput], showing its currently bound [ButtonAction]
 * and delegating "change this mapping" taps back to the hosting activity, which owns
 * the dialog UI and persistence.
 */
class ButtonMappingAdapter(
    private val rows: MutableList<Pair<ControllerInput, ButtonAction>>,
    private val onEditRequested: (ControllerInput, ButtonAction) -> Unit
) : RecyclerView.Adapter<ButtonMappingAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemButtonMappingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemButtonMappingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (input, action) = rows[position]
        holder.binding.textInputName.text = input.name
        holder.binding.textActionSummary.text = summarize(action)
        holder.binding.buttonChangeMapping.setOnClickListener { onEditRequested(input, action) }
    }

    override fun getItemCount(): Int = rows.size

    /** Replaces the action bound to [input] and redraws just that row. */
    fun updateAction(input: ControllerInput, action: ButtonAction) {
        val index = rows.indexOfFirst { it.first == input }
        if (index == -1) return
        rows[index] = input to action
        notifyItemChanged(index)
    }

    private fun summarize(action: ButtonAction): String {
        val base = action.type.name.replace('_', ' ')
        val direction = action.swipeDirection?.let { " (${it.name})" } ?: ""
        return base + direction
    }
}
