package app.vinkje.ui

import app.vinkje.sync.assigneeSet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.vinkje.data.TodoRepository
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Recurrence.Freq
import app.vinkje.domain.Step
import app.vinkje.domain.TaskBodyCodec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Task detail, in To Do's order: title, steps (each one tap to tick, like the task
 * itself), Mijn dag, reminder, due date, repeat, note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(repo: TodoRepository, taskId: Long, onBack: () -> Unit) {
    val task by repo.task(taskId).collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val t = task ?: return
    val body = remember(t.description) { TaskBodyCodec.parse(t.description) }
    val today = repo.today()
    val due = t.dueEpochSecond?.let(::localDate)
    val rule = body.recurrence
    val members by repo.members(t.id).collectAsStateWithLifecycle(emptyMap())
    val self by repo.selfUid.collectAsStateWithLifecycle()

    // Title and note are edited locally and saved after a short pause in typing.
    var title by rememberSaveable(t.id) { mutableStateOf(t.title) }
    var note by rememberSaveable(t.id) { mutableStateOf(body.note) }
    LaunchedEffect(title) { delay(600); if (title != t.title) repo.rename(t.id, title) }
    LaunchedEffect(note) { delay(600); if (note != body.note) repo.updateBody(t.id) { it.copy(note = note) } }

    fun setSteps(change: (List<Step>) -> List<Step>) = scope.launch { repo.updateBody(t.id) { it.copy(steps = change(it.steps)) } }

    var picker by remember { mutableStateOf<Picker?>(null) }
    var pickedReminderDate by remember { mutableStateOf<LocalDate?>(null) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun setReminder(at: LocalDateTime) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch { repo.setReminder(t.id, at.atZone(ZoneId.systemDefault()).toEpochSecond()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
                actions = {
                    IconButton(onClick = { scope.launch { repo.setImportant(t.id, !t.important) } }) {
                        Icon(if (t.important) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = "Belangrijk")
                    }
                    IconButton(onClick = { scope.launch { repo.delete(t.id) }; onBack() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Taak verwijderen")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckCircle(checked = t.doneEpochSecond != null, taskTitle = t.title, onToggle = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    scope.launch { if (t.doneEpochSecond == null) repo.complete(t.id) else repo.reopen(t.id) }
                })
                TextField(
                    value = title,
                    onValueChange = { title = it.replace('\n', ' ') },
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    modifier = Modifier.weight(1f),
                )
            }

            body.steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    CheckCircle(checked = step.done, taskTitle = step.text, onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        setSteps { steps -> steps.mapIndexed { i, s -> if (i == index) s.copy(done = !s.done) else s } }
                    })
                    Text(
                        step.text,
                        textDecoration = if (step.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { setSteps { steps -> steps.filterIndexed { i, _ -> i != index } } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stap verwijderen")
                    }
                }
            }
            AddStepField(onAdd = { text -> setSteps { it + Step(text) } })

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            val inMyDay = t.myDay == today.toString()
            OptionRow(
                icon = Icons.Outlined.WbSunny,
                label = if (inMyDay) "Toegevoegd aan Mijn dag" else "Toevoegen aan Mijn dag",
                active = inMyDay,
                onClick = { scope.launch { repo.setMyDay(t.id, !inMyDay) } },
                onClear = if (inMyDay) ({ scope.launch { repo.setMyDay(t.id, false) } }) else null,
            )

            val reminder = t.reminderEpochSecond?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
            OptionRow(
                icon = Icons.Outlined.Notifications,
                label = reminder?.let { "Herinnering om ${it.format(timeFormat)}" } ?: "Herinner mij",
                detail = reminder?.let { formatDue(it.toLocalDate(), today) },
                active = reminder != null,
                onClear = if (reminder != null) ({ scope.launch { repo.setReminder(t.id, null) } }) else null,
                menu = { close ->
                    val now = LocalDateTime.now()
                    val later = now.plusHours(3).withMinute(0).withSecond(0).withNano(0)
                    if (later.toLocalDate() == today) {
                        DropdownMenuItem(text = { Text("Later vandaag (${later.format(timeFormat)})") }, onClick = { close(); setReminder(later) })
                    }
                    DropdownMenuItem(text = { Text("Morgen (9:00)") }, onClick = { close(); setReminder(today.plusDays(1).atTime(9, 0)) })
                    DropdownMenuItem(text = { Text("Volgende week (ma 9:00)") }, onClick = { close(); setReminder(nextMonday(today).atTime(9, 0)) })
                    DropdownMenuItem(text = { Text("Datum en tijd kiezen") }, onClick = { close(); picker = Picker.REMINDER_DATE })
                },
            )

            OptionRow(
                icon = Icons.Outlined.CalendarMonth,
                label = due?.let { "Vervalt ${formatDue(it, today).replaceFirstChar(Char::lowercase)}" } ?: "Vervaldatum toevoegen",
                active = due != null,
                activeColor = if (due != null && due < today && t.doneEpochSecond == null) MaterialTheme.colorScheme.error else null,
                onClear = if (due != null) ({ scope.launch { repo.setDue(t.id, null) } }) else null,
                menu = { close ->
                    DropdownMenuItem(text = { Text("Vandaag") }, onClick = { close(); scope.launch { repo.setDue(t.id, today) } })
                    DropdownMenuItem(text = { Text("Morgen") }, onClick = { close(); scope.launch { repo.setDue(t.id, today.plusDays(1)) } })
                    DropdownMenuItem(text = { Text("Volgende week") }, onClick = { close(); scope.launch { repo.setDue(t.id, nextMonday(today)) } })
                    DropdownMenuItem(text = { Text("Datum kiezen") }, onClick = { close(); picker = Picker.DUE })
                },
            )

            OptionRow(
                icon = Icons.Outlined.Repeat,
                label = rule?.let(::describeRecurrence) ?: if (body.meta.containsKey("Herhaling")) "Herhaalt (eigen regel)" else "Herhalen",
                active = rule != null,
                onClear = if (rule != null) ({ scope.launch { repo.setRecurrence(t.id, null) } }) else null,
                menu = { close ->
                    val anchor = due ?: today
                    fun pick(r: Recurrence) { close(); scope.launch { repo.setRecurrence(t.id, r) } }
                    DropdownMenuItem(text = { Text("Dagelijks") }, onClick = { pick(Recurrence(Freq.DAILY)) })
                    DropdownMenuItem(text = { Text("Werkdagen") }, onClick = { pick(Recurrence.workdays()) })
                    DropdownMenuItem(text = { Text("Wekelijks") }, onClick = { pick(Recurrence.anchored(Freq.WEEKLY, anchor)) })
                    DropdownMenuItem(text = { Text("Maandelijks") }, onClick = { pick(Recurrence.anchored(Freq.MONTHLY, anchor)) })
                    DropdownMenuItem(text = { Text("Jaarlijks") }, onClick = { pick(Recurrence.anchored(Freq.YEARLY, anchor)) })
                    DropdownMenuItem(text = { Text("Aangepast") }, onClick = { close(); picker = Picker.RECURRENCE })
                },
            )

            // Only a shared group has someone to assign to.
            if (members.size > 1) {
                val assigned = assigneeSet(t.assignees)
                fun name(uid: String) = if (uid == self) "jou" else members[uid] ?: "onbekend"
                OptionRow(
                    icon = Icons.Outlined.PersonAddAlt,
                    label = if (assigned.isEmpty()) "Toewijzen" else "Toegewezen aan " + assigned.sortedBy { it != self }.joinToString(" en ", transform = ::name),
                    active = assigned.isNotEmpty(),
                    onClear = if (assigned.isNotEmpty()) ({ scope.launch { assigned.forEach { repo.setAssigned(t.id, it, false) } } }) else null,
                    menu = { close ->
                        members.entries.sortedBy { it.key != self }.forEach { (uid, displayName) ->
                            val on = uid in assigned
                            DropdownMenuItem(
                                text = { Text(if (uid == self) "$displayName (jij)" else displayName) },
                                leadingIcon = { Icon(if (on) Icons.Filled.Check else Icons.Outlined.PersonOutline, contentDescription = if (on) "Toegewezen" else null) },
                                onClick = { close(); scope.launch { repo.setAssigned(t.id, uid, !on) } },
                            )
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Notitie toevoegen") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }

    when (picker) {
        Picker.DUE -> DateDialog(initial = due ?: today, onDismiss = { picker = null }) {
            picker = null
            scope.launch { repo.setDue(t.id, it) }
        }
        Picker.REMINDER_DATE -> DateDialog(initial = today, onDismiss = { picker = null }) {
            pickedReminderDate = it
            picker = Picker.REMINDER_TIME
        }
        Picker.REMINDER_TIME -> TimeDialog(initial = LocalTime.of(9, 0), onDismiss = { picker = null }) { time ->
            picker = null
            pickedReminderDate?.let { setReminder(it.atTime(time)) }
        }
        Picker.RECURRENCE -> RecurrenceDialog(initial = rule, anchor = due ?: today, onDismiss = { picker = null }) {
            picker = null
            scope.launch { repo.setRecurrence(t.id, it) }
        }
        null -> {}
    }
}

private enum class Picker { DUE, REMINDER_DATE, REMINDER_TIME, RECURRENCE }

private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

private fun nextMonday(today: LocalDate) = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

/**
 * A To Do detail row: icon and label, tinted once set, with a cross to clear it.
 * With a [menu] a tap opens the quick choices; without one it runs [onClick].
 */
@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    active: Boolean,
    detail: String? = null,
    activeColor: Color? = null,
    onClick: () -> Unit = {},
    onClear: (() -> Unit)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val color = if (active) activeColor ?: MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable { if (menu != null) open = true else onClick() }
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (onClear != null) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "$label weghalen") }
            }
        }
        if (menu != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

@Composable
private fun AddStepField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    TextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(if (text.isEmpty()) "Stap toevoegen" else "Volgende stap") },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (text.isNotBlank()) onAdd(text.trim())
            text = ""
        }),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
    )
}
