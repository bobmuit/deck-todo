package app.vinkje.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Recurrence.Freq
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneOffset
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material date picker works in UTC midnights.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
        text = { TimePicker(state) },
    )
}

/** To Do's "Aangepast": every n days, weeks (on chosen weekdays), months or years. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceDialog(initial: Recurrence?, anchor: LocalDate, onDismiss: () -> Unit, onPick: (Recurrence) -> Unit) {
    var interval by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var freq by remember { mutableStateOf(initial?.freq ?: Freq.WEEKLY) }
    var days by remember { mutableStateOf(initial?.byDay?.takeIf { it.isNotEmpty() } ?: setOf(anchor.dayOfWeek)) }
    val n = interval.toIntOrNull()?.takeIf { it in 1..999 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Herhalen") },
        confirmButton = {
            TextButton(enabled = n != null && (freq != Freq.WEEKLY || days.isNotEmpty()), onClick = {
                val count = n ?: return@TextButton
                onPick(
                    when (freq) {
                        Freq.WEEKLY -> Recurrence(Freq.WEEKLY, count, byDay = days)
                        else -> Recurrence.anchored(freq, anchor, count)
                    }
                )
            }) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Elke")
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp).padding(horizontal = 8.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val plural = n != null && n > 1
                    for ((f, label) in listOf(
                        Freq.DAILY to if (plural) "dagen" else "dag",
                        Freq.WEEKLY to if (plural) "weken" else "week",
                        Freq.MONTHLY to if (plural) "maanden" else "maand",
                        Freq.YEARLY to "jaar",
                    )) {
                        FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(label) })
                    }
                }
                if (freq == Freq.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in DayOfWeek.entries) {
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, dutch)) },
                            )
                        }
                    }
                }
            }
        },
    )
}

fun describeRecurrence(rule: Recurrence): String {
    val n = rule.interval
    return when (rule.freq) {
        Freq.DAILY -> if (n == 1) "Dagelijks" else "Elke $n dagen"
        Freq.WEEKLY -> when {
            rule.isWorkdays -> "Werkdagen"
            else -> {
                val base = if (n == 1) "Wekelijks" else "Elke $n weken"
                val days = rule.byDay.sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, dutch) }
                if (days.isEmpty()) base else "$base op $days"
            }
        }
        Freq.MONTHLY -> (if (n == 1) "Maandelijks" else "Elke $n maanden") + (rule.byMonthDay?.let { " op de ${it}e" } ?: "")
        Freq.YEARLY -> (if (n == 1) "Jaarlijks" else "Elke $n jaar") +
            (rule.byMonth?.let { month -> rule.byMonthDay?.let { " op $it ${Month.of(month).getDisplayName(TextStyle.FULL, dutch)}" } } ?: "")
    }
}
