package dev.mahlernim.timelinevisualizer.ui

import android.app.Activity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.SearchView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.databinding.DialogTripZoneBinding
import java.time.ZoneId

internal fun Activity.showTripZonePicker(current: ZoneId, onSelected: (ZoneId) -> Unit) {
    val picker = DialogTripZoneBinding.inflate(layoutInflater)
    val zones = (ZoneId.getAvailableZoneIds() + current.id).sorted()
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.trip_time_zone)
        .setView(picker.root)
        .setNegativeButton(R.string.cancel, null)
        .create()

    fun showMatches(query: String) {
        val term = query.trim().replace(' ', '_')
        val matches = zones.filter { it.contains(term, ignoreCase = true) }
        picker.zones.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, matches)
        val selected = matches.indexOf(current.id)
        if (selected >= 0) {
            picker.zones.setItemChecked(selected, true)
            if (term.isEmpty()) picker.zones.setSelection(selected)
        }
    }

    picker.zoneSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String) = false
        override fun onQueryTextChange(newText: String): Boolean {
            showMatches(newText)
            return true
        }
    })
    picker.zones.setOnItemClickListener { parent, _, index, _ ->
        val selected = ZoneId.of(parent.getItemAtPosition(index) as String)
        dialog.dismiss()
        onSelected(selected)
    }
    showMatches("")
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.show()
}
