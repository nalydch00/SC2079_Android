package com.sc2079.mdp.ui

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sc2079.mdp.R
import com.sc2079.mdp.util.Prefs

/**
 * Lets the team retune the movement command strings without a rebuild, which is
 * what integration week usually needs.
 */
object CommandSettingsDialog {

    fun show(context: Context, prefs: Prefs, onSaved: () -> Unit) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        val fields = Prefs.CommandAction.entries.associateWith { action ->
            val layout = TextInputLayout(context).apply {
                hint = action.label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val edit = TextInputEditText(context).apply {
                setText(prefs.command(action))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            layout.addView(edit)
            container.addView(layout)
            edit
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_commands)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton(R.string.save) { _, _ ->
                fields.forEach { (action, edit) -> prefs.setCommand(action, edit.text?.toString().orEmpty()) }
                onSaved()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                prefs.resetCommands()
                onSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** Changes the arena grid size, for practising on smaller maps. */
object ArenaSizeDialog {

    fun show(context: Context, columns: Int, rows: Int, onChosen: (Int, Int) -> Unit) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        fun field(hint: String, value: Int): TextInputEditText {
            val layout = TextInputLayout(context).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val edit = TextInputEditText(context).apply {
                setText(value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine()
            }
            layout.addView(edit)
            container.addView(layout)
            return edit
        }

        val columnField = field(context.getString(R.string.columns), columns)
        val rowField = field(context.getString(R.string.rows), rows)

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_arena)
            .setMessage(R.string.settings_arena_warning)
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ ->
                val newColumns = columnField.text?.toString()?.toIntOrNull()?.coerceIn(5, 40) ?: columns
                val newRows = rowField.text?.toString()?.toIntOrNull()?.coerceIn(5, 40) ?: rows
                onChosen(newColumns, newRows)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
