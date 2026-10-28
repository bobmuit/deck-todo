package app.vinkje.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import app.vinkje.data.TaskEntity
import app.vinkje.data.TodoRepository
import app.vinkje.sync.assigneeSet
import app.vinkje.domain.TaskBodyCodec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One To Do list (a Deck stack). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(repo: TodoRepository, listId: Long, onBack: () -> Unit, onOpenTask: (Long) -> Unit) {
    val list by repo.list(listId).collectAsStateWithLifecycle(null)
    val tasks by repo.tasks(listId).collectAsStateWithLifecycle(null)
    val groupId = list?.groupId
    val group by remember(groupId) { groupId?.let(repo::group) ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<Long?>(null) }
    val doneCount = tasks.orEmpty().count { it.doneEpochSecond != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
                },
                actions = {
                    // Deck lets only those who manage a board rename, move or remove its stacks;
                    // removing cards only needs edit rights. Pinning is this phone's own business.
                    val items = buildList {
                        if (list != null) add("pin" to if (list?.pinned != null) "Losmaken van startscherm" else "Vastpinnen op startscherm")
                        if (group?.canEdit == true && doneCount > 0) add("clearDone" to "Voltooide taken verwijderen")
                        if (group?.canManage == true) addAll(listOf("rename" to "Naam wijzigen", "move" to "Verplaatsen naar groep", "delete" to "Lijst verwijderen"))
                    }
                    if (items.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Lijstopties") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                for ((key, label) in items) {
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        menu = false
                                        if (key == "pin") scope.launch { repo.setPinned(listId, list?.pinned == null) } else dialog = key
                                    })
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { AddTaskBar(onAdd = { title -> scope.launch { added = repo.addTask(listId, title) } }) },
    ) { padding ->
        TaskColumn(repo, tasks, snackbar, onOpenTask, Modifier.padding(padding), reveal = added)
    }

    val current = list ?: return
    when (dialog) {
        "rename" -> NameDialog("Lijst hernoemen", initial = current.title, onDismiss = { dialog = null }) { title ->
            dialog = null
            scope.launch { repo.renameList(listId, title) }
        }
        "move" -> MoveListDialog(repo, current.groupId, onDismiss = { dialog = null }) { target ->
            dialog = null
            scope.launch { repo.moveList(listId, target) }
        }
        "clearDone" -> ConfirmDialog(
            title = "Voltooide taken verwijderen?",
            text = (if (doneCount == 1) "1 voltooide taak wordt" else "$doneCount voltooide taken worden") + " verwijderd uit „${current.title}”" +
                (if (group?.sharedWith.isNullOrEmpty()) "." else ", ook voor ${group?.sharedWith}."),
            confirm = "Verwijderen",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch {
                val ids = repo.deleteDone(listId)
                if (ids.isEmpty()) return@launch
                val message = if (ids.size == 1) "1 voltooide taak verwijderd" else "${ids.size} voltooide taken verwijderd"
                scope.undoSnackbar(snackbar, message) { ids.forEach { repo.undoDelete(it) } }
            }
        }
        "delete" -> ConfirmDialog(
            title = "Lijst verwijderen?",
            text = "„${current.title}” en de taken erin worden verwijderd" +
                (if (group?.sharedWith.isNullOrEmpty()) "." else ", ook voor ${group?.sharedWith}."),
            confirm = "Verwijderen",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            // Leave once the row is marked: leaving first would cancel this scope.
            scope.launch { repo.deleteList(listId); onBack() }
        }
    }
}

/**
 * The task list shared by real and smart lists. The design rule of the whole app lives
 * here: ticking a task off is a single tap on the circle, written locally and shown at
 * once, with undo.
 *
 * [loaded] holds the open tasks in display order plus recently done ones: a done task
 * shows under "Voltooid" when [showDone], and otherwise only while it is being ticked.
 * Null means not loaded yet: nothing is drawn, as an empty list would lose the scroll
 * position kept for coming back from a task.
 *
 * [reveal] is a task just added from the bar, scrolled into view once it shows up: the
 * list keeps its top row in place, so a task added above it would land out of sight.
 */
@Composable
fun TaskColumn(
    repo: TodoRepository,
    loaded: List<TaskEntity>?,
    snackbar: SnackbarHostState,
    onOpenTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showDone: Boolean = true,
    listTitles: Map<Long, String>? = null,
    sectionOf: ((TaskEntity) -> String)? = null,
    emptyText: String? = null,
    reveal: Long? = null,
) {
    val tasks = loaded.orEmpty()
    // A just-ticked task stays in place for a moment with its check visible, then moves
    // to "Voltooid", as in To Do. The database write does not wait for this. Held here is
    // its place among the open rows: a list's query sorts done tasks last, and the list
    // would scroll along if its top row moved to the end.
    val holding = remember { mutableStateMapOf<Long, Int>() }
    val open = tasks.filter { it.doneEpochSecond == null && it.id !in holding }.toMutableList().apply {
        for ((id, index) in holding.entries.sortedBy { it.value }) {
            tasks.firstOrNull { it.id == id }?.let { add(index.coerceAtMost(size), it) }
        }
    }
    val done = if (!showDone) emptyList() else tasks.filter { it.doneEpochSecond != null && it.id !in holding }.sortedByDescending { it.doneEpochSecond }
    var doneExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val today = repo.today()
    val people by repo.people().collectAsStateWithLifecycle(emptyMap())

    // The circle reacts in the same frame as the tap; the row moves once the database confirms.
    // The override outlives the confirmation by the item animation, or the row fading out
    // of the open section would briefly show an empty circle again.
    val ticking = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(tasks) {
        val confirmed = tasks.filter { ticking[it.id] == (it.doneEpochSecond != null) }.map { it.id to ticking[it.id] }
        if (confirmed.isNotEmpty()) {
            delay(1000)
            confirmed.forEach { (id, value) -> if (ticking[id] == value) ticking.remove(id) }
        }
    }

    fun toggle(task: TaskEntity) {
        val nowDone = task.doneEpochSecond == null
        ticking[task.id] = nowDone
        haptics.performHapticFeedback(if (nowDone) HapticFeedbackType.Confirm else HapticFeedbackType.ToggleOff)
        if (nowDone) {
            holding[task.id] = open.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
            scope.launch { delay(450); holding -= task.id }
            scope.launch {
                val completion = repo.complete(task.id) ?: return@launch
                val message = completion.nextDue?.let { "Voltooid: ${task.title}. Volgende: ${formatDue(it, today).replaceFirstChar(Char::lowercase)}" } ?: "Voltooid: ${task.title}"
                scope.undoSnackbar(snackbar, message) { repo.undoComplete(completion) }
            }
        } else {
            scope.launch { repo.reopen(task.id) }
        }
    }

    fun delete(task: TaskEntity) {
        scope.launch { repo.delete(task.id) }
        scope.undoSnackbar(snackbar, "Taak verwijderd") { repo.undoDelete(task.id) }
    }

    @Composable
    fun Item(task: TaskEntity, modifier: Modifier) {
        SwipeToDelete(onDelete = { delete(task) }, modifier = modifier) {
            TaskRow(
                task, shownDone = ticking[task.id], today = today, listTitle = listTitles?.get(task.listId),
                assignees = assigneeSet(task.assignees).map { people[it] ?: "?" },
                onToggle = { toggle(task) },
                onStar = { scope.launch { repo.setImportant(task.id, !task.important) } },
                onOpen = { onOpenTask(task.id) },
            )
        }
    }

    val listState = rememberLazyListState()
    var revealed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(reveal, open) {
        if (reveal == null || reveal == revealed) return@LaunchedEffect
        // Its index in the LazyColumn below, past the section headings before it.
        var index = 0
        for (section in if (sectionOf == null) listOf(open) else open.groupBy(sectionOf).values) {
            if (sectionOf != null) index++
            val i = section.indexOfFirst { it.id == reveal }
            if (i < 0) {
                index += section.size
                continue
            }
            withFrameNanos {} // the list lays the new row out first
            val layout = listState.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == "open-$reveal" }
            if (row == null || row.offset < 0 || row.offset + row.size > layout.viewportEndOffset) listState.animateScrollToItem(index + i)
            revealed = reveal
            return@LaunchedEffect
        }
    }

    if (loaded == null) return
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (open.isEmpty() && done.isEmpty() && emptyText != null) {
            item(key = "empty") {
                Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        // Open and done rows have their own keys: the list keeps its scroll position on the
        // key of the top row, so with a shared key ticking that row off would scroll along
        // with it down to "Voltooid".
        if (sectionOf == null) {
            items(open, key = { "open-${it.id}" }) { Item(it, Modifier.animateItem()) }
        } else {
            for ((section, sectionTasks) in open.groupBy(sectionOf)) {
                item(key = "section-$section") {
                    Text(
                        section, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.animateItem().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(sectionTasks, key = { "open-${it.id}" }) { Item(it, Modifier.animateItem()) }
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done-header") {
                Row(
                    modifier = Modifier.animateItem().clickable { doneExpanded = !doneExpanded }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (doneExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    Text("Voltooid  ${done.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (doneExpanded) {
                items(done, key = { "done-${it.id}" }) { Item(it, Modifier.animateItem()) }
            }
        }
    }
}

fun CoroutineScope.undoSnackbar(host: SnackbarHostState, message: String, undo: suspend () -> Unit) = launch {
    host.currentSnackbarData?.dismiss()
    val result = host.showSnackbar(message, actionLabel = "Ongedaan maken", duration = SnackbarDuration.Short)
    if (result == SnackbarResult.ActionPerformed) undo()
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    shownDone: Boolean?,
    today: LocalDate,
    listTitle: String?,
    assignees: List<String>,
    onToggle: () -> Unit,
    onStar: () -> Unit,
    onOpen: () -> Unit,
) {
    val isDone = shownDone ?: (task.doneEpochSecond != null)
    val body = remember(task.description) { TaskBodyCodec.parse(task.description) }
    val due = task.dueEpochSecond?.let(::localDate)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dueColor = when {
        isDone || due == null -> muted
        due < today -> MaterialTheme.colorScheme.error
        due == today -> MaterialTheme.colorScheme.primary
        else -> muted
    }
    val parts = buildList<Pair<String, Color>> {
        listTitle?.let { add(it to muted) }
        if (task.myDay == today.toString() && listTitle == null) add("Mijn dag" to muted)
        if (body.steps.isNotEmpty()) add("${body.steps.count { it.done }} van ${body.steps.size}" to muted)
        due?.let { add((formatDue(it, today) + if (body.recurrence != null) " ↻" else "") to dueColor) }
        if (task.reminderEpochSecond != null && !isDone) add("Herinnering" to muted)
        if (body.note.isNotBlank()) add("Notitie" to muted)
    }
    val subtitle = buildAnnotatedString {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) withStyle(SpanStyle(color = muted)) { append("  ·  ") }
            withStyle(SpanStyle(color = color)) { append(text) }
        }
    }

    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.clickable(onClick = onOpen).heightIn(min = 56.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckCircle(checked = isDone, onToggle = onToggle, taskTitle = task.title)
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                    color = if (isDone) muted else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (assignees.isNotEmpty()) Avatar(assignees)
            IconButton(onClick = onStar) {
                Icon(
                    if (task.important) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (task.important) "Niet meer belangrijk" else "Belangrijk",
                    tint = if (task.important) MaterialTheme.colorScheme.primary else muted,
                )
            }
        }
    }
}

/** To Do's small initials circle for who a task is assigned to; "+1" when more people share it. */
@Composable
private fun Avatar(names: List<String>) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(26.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            .semantics { contentDescription = "Toegewezen aan ${names.joinToString(" en ")}" },
    ) {
        Text(
            if (names.size == 1) names[0].take(1).uppercase() else "+${names.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** The To Do circle. The whole 56dp square at the start of the row is the tap target. */
@Composable
fun CheckCircle(checked: Boolean, onToggle: () -> Unit, taskTitle: String) {
    val fill by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, label = "fill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = if (checked) "$taskTitle: voltooid, tik om terug te zetten" else "$taskTitle: tik om af te vinken" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(24.dp).background(fill, CircleShape)
                .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it != SwipeToDismissBoxValue.Settled) onDelete()
            it != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(horizontal = 20.dp),
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Verwijderen") }
        },
        enableDismissFromStartToEnd = false,
    ) { content() }
}

/**
 * Enter adds the task and keeps the keyboard open, so a shopping list goes in one go.
 * [trailing] is where a smart list shows (and lets you change) the list new tasks go to.
 */
@Composable
fun AddTaskBar(onAdd: (String) -> Unit, placeholder: String = "Taak toevoegen", trailing: (@Composable () -> Unit)? = null) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            trailingIcon = trailing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

val dutch: Locale = Locale.forLanguageTag("nl")
private val dueFormat = DateTimeFormatter.ofPattern("EEE d MMM", dutch)
private val dueFormatOtherYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy", dutch)

fun localDate(epochSecond: Long): LocalDate = Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()).toLocalDate()

fun formatDue(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Vandaag"
    today.plusDays(1) -> "Morgen"
    today.minusDays(1) -> "Gisteren"
    else -> date.format(if (date.year == today.year) dueFormat else dueFormatOtherYear)
}
