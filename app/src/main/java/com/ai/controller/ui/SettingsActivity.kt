package com.ai.controller.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.ai.controller.ProfileManager
import com.ai.controller.R
import com.ai.controller.databinding.ActivitySettingsBinding
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection

/**
 * Per-input mapping editor. Tapping "Change" on a row opens a two-step picker:
 * choose an [ActionType], then (for SWIPE/SCROLL) a [SwipeDirection]. Every
 * change is persisted immediately via [ProfileManager] so it takes effect the
 * next time the accessibility service reads that input.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var profileManager: ProfileManager
    private lateinit var profile: ControllerProfile
    private lateinit var adapter: ButtonMappingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        profileManager = ProfileManager(this)
        profile = profileManager.loadProfile()

        val rows = ControllerInput.values().map { input ->
            input to (profile.mappings[input] ?: ButtonAction.none())
        }.toMutableList()

        adapter = ButtonMappingAdapter(rows) { input, currentAction -> showActionTypeDialog(input, currentAction) }

        binding.recyclerButtonMappings.layoutManager = LinearLayoutManager(this)
        binding.recyclerButtonMappings.adapter = adapter
        binding.recyclerButtonMappings.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
    }

    private fun showActionTypeDialog(input: ControllerInput, currentAction: ButtonAction) {
        val types = ActionType.values()
        val labels = types.map { it.name.replace('_', ' ') }.toTypedArray()
        val preselected = types.indexOf(currentAction.type).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_dialog_title)
            .setSingleChoiceItems(labels, preselected) { dialog, which ->
                dialog.dismiss()
                val chosenType = types[which]
                if (chosenType == ActionType.SWIPE || chosenType == ActionType.SCROLL) {
                    showDirectionDialog(input, chosenType, currentAction)
                } else {
                    applyMapping(input, ButtonAction(type = chosenType, keyCode = currentAction.keyCode))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDirectionDialog(input: ControllerInput, type: ActionType, currentAction: ButtonAction) {
        val directions = SwipeDirection.values()
        val labels = directions.map { it.name }.toTypedArray()
        val preselected = directions.indexOf(currentAction.swipeDirection).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_dialog_title)
            .setSingleChoiceItems(labels, preselected) { dialog, which ->
                dialog.dismiss()
                applyMapping(input, ButtonAction(type = type, swipeDirection = directions[which]))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyMapping(input: ControllerInput, action: ButtonAction) {
        profile.mappings[input] = action
        profileManager.saveProfile(profile)
        adapter.updateAction(input, action)
    }
}
