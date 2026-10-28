package app.vinkje.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.vinkje.data.TaskEntity
import app.vinkje.data.TodoRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

enum class SmartList(val title: String, val icon: ImageVector, val empty: String) {
    MY_DAY("Mijn dag", Icons.Outlined.WbSunny, "Zet taken in Mijn dag om je op vandaag te richten. Morgen begint de lijst weer leeg."),
    IMPORTANT("Belangrijk", Icons.Outlined.StarOutline, "Geef een taak een ster om hem hier te zien."),
    PLANNED("Gepland", Icons.Outlined.CalendarMonth, "Taken met een vervaldatum verschijnen hier."),
    ASSIGNED("Aan mij toegewezen", Icons.Outlined.PersonOutline, "Taken die iemand aan jou toewijst, verschijnen hier."),
}

private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", dutch)

/** Mijn dag, Belangrijk and Gepland: views across all visible lists, as in To Do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListScreen(repo: TodoRepository, kind: SmartList, onBack: () -> Unit, onOpenTask: (Long) -> Unit) {
    val today = repo.today()
    // Done tasks come along from this moment on, so a ticked row can finish its animation
    // and its undo; Mijn dag keeps everything ticked today under "Voltooid".
    val since = remember(kind) {
        if (kind == SmartList.MY_DAY) today.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        else System.currentTimeMillis() / 1000 - 60
    }
    val flow = remember(kind, since) {
        when (kind) {
            SmartList.MY_DAY -> repo.myDay(since)
            SmartList.IMPORTANT -> repo.important(since)
            SmartList.PLANNED -> repo.planned(since)
            SmartList.ASSIGNED -> repo.assignedToMe(since)
        }
    }
    val rows by flow.collectAsStateWithLifecycle(null)
    val tasks = rows?.map { it.task }
    val listTitles = rows.orEmpty().associate { it.task.listId to it.listTitle }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val lists by repo.writableLists().collectAsStateWithLifecycle(emptyList())
    var destination by remember { mutableStateOf<Long?>(null) }
    var added by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lists) {
        if (destination == null || lists.none { it.id == destination }) destination = repo.defaultListId()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(kind.title)
                        if (kind == SmartList.MY_DAY) {
                            Text(today.format(longDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val target = destination
            // As in To Do, tasks are assigned from a list, not typed into this view.
            if (target != null && kind != SmartList.ASSIGNED) {
                AddTaskBar(
                    onAdd = { title ->
                        scope.launch {
                            added = repo.addTask(
                                target, title,
                                important = kind == SmartList.IMPORTANT,
                                due = if (kind == SmartList.PLANNED) today else null,
                                myDay = kind == SmartList.MY_DAY,
                            )
                        }
                    },
                    trailing = {
                        ListPicker(
                            current = lists.firstOrNull { it.id == target }?.title ?: "",
                            options = lists.map { it.id to it.title },
                            onPick = { destination = it; repo.setDefaultList(it) },
                        )
                    },
                )
            }
        },
    ) { padding ->
        TaskColumn(
            repo, tasks, snackbar, onOpenTask, Modifier.padding(padding),
            showDone = kind == SmartList.MY_DAY,
            listTitles = listTitles,
            sectionOf = if (kind == SmartList.PLANNED) { task -> plannedSection(task, today) } else null,
            emptyText = kind.empty,
            reveal = added,
        )
    }
}

/** Gepland's headings, as in To Do: Eerder, Vandaag, Morgen, the rest of this week by day, Later. */
private fun plannedSection(task: TaskEntity, today: LocalDate): String {
    val due = localDate(task.dueEpochSecond ?: return "Later")
    return when {
        due < today -> "Eerder"
        due == today -> "Vandaag"
        due == today.plusDays(1) -> "Morgen"
        due < today.plusDays(7) -> due.dayOfWeek.getDisplayName(TextStyle.FULL, dutch).replaceFirstChar { it.uppercase() }
        else -> "Later"
    }
}

@Composable
private fun ListPicker(current: String, options: List<Pair<Long, String>>, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 160.dp)) {
            Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onPick(id); open = false })
            }
        }
    }
}
