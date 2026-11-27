package com.wanderlust.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wanderlust.app.domain.shortDateFmt
import com.wanderlust.app.domain.timeFmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** A read-only text field that opens a picker when tapped. */
@Composable
private fun PickerField(label: String, value: String, modifier: Modifier, onClear: (() -> Unit)?, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Release) onOpen() }
    }
    OutlinedTextField(
        value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
        modifier = modifier, interactionSource = source, singleLine = true,
        trailingIcon = if (onClear != null && value.isNotBlank()) {
            { IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear") } }
        } else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, value?.format(shortDateFmt) ?: "", modifier, { onChange(null) }) { open = true }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (value ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: LocalTime?, onChange: (LocalTime?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, value?.format(timeFmt) ?: "", modifier, { onChange(null) }) { open = true }
    if (open) {
        val state = rememberTimePickerState(initialHour = value?.hour ?: 9, initialMinute = value?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

/** Date + time side by side, bound to a single LocalDateTime. */
@Composable
fun DateTimeField(label: String, value: LocalDateTime?, onChange: (LocalDateTime?) -> Unit, modifier: Modifier = Modifier, defaultDate: LocalDate? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField("$label date", value?.toLocalDate(), { d ->
            onChange(d?.atTime(value?.toLocalTime() ?: LocalTime.of(12, 0)))
        }, Modifier.weight(1.3f))
        TimeField("Time", value?.toLocalTime(), { t ->
            val d = value?.toLocalDate() ?: defaultDate ?: LocalDate.now()
            onChange(t?.let { d.atTime(it) } ?: value?.toLocalDate()?.atTime(12, 0))
        }, Modifier.weight(1f))
    }
}
