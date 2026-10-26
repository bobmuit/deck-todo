package app.vinkje.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.vinkje.data.GroupEntity
import app.vinkje.data.HomeData
import app.vinkje.data.ListWithCount
import app.vinkje.data.TodoRepository
import app.vinkje.domain.HomeLayout
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * "Volgorde aanpassen": the home screen with drag handles and pins. Dragging changes a
 * working copy only; "Klaar" (or back) saves it, so no half-done order reaches Deck.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangeScreen(repo: TodoRepository, home: HomeData, onDone: () -> Unit) {
    var layout by remember { mutableStateOf(repo.layout(home)) }
    /** The group being dragged: its lists are left out, so it moves as one row. */
    var draggedGroup by remember { mutableStateOf<Long?>(null) }
    /** The key of the row being dragged. */
    var dragged by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        layout.move(from.key as String, to.key as String)?.let { layout = it }
    }
    val groups = home.groups.associateBy { it.id }
    val lists = home.lists.associateBy { it.id }
    val done: () -> Unit = { scope.launch { repo.saveLayout(layout); onDone() } }
    val (pinnedRows, rows) = layout.rows(draggedGroup).partition { it is HomeLayout.Row.Pinned }
    // Only rows the dragged row may move to are targets. Reorderable takes a refused move for one
    // that is still on its way: the dragged row then lags behind the finger, a bit more with every
    // refusal, and never reaches the end of a group to pass it.
    fun isTarget(key: String) = dragged.let { it == null || it == key || layout.move(it, key) != null }
    val onDragging = { key: String -> { started: Boolean -> dragged = key.takeIf { started } } }
    BackHandler(onBack = done)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Volgorde aanpassen") },
                actions = { TextButton(onClick = done) { Text("Klaar") } },
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (pinnedRows.isNotEmpty()) {
                item(key = "pinnedLabel") { SectionLabel("Vastgepind") }
                items(pinnedRows, key = { it.key }) { row ->
                    val list = lists[(row as HomeLayout.Row.Pinned).listId] ?: return@items
                    val group = groups[list.groupId] ?: return@items
                    ReorderableItem(reorder, row.key, enabled = isTarget(row.key)) { dragging ->
                        ArrangeListRow(list, group, subtitle = group.title.takeUnless { repo.isLoose(group) }, dragging, pinned = true, draggable = true,
                            onPin = { layout = layout.pin(list.id, false) }, onDragging = onDragging(row.key))
                    }
                }
                item(key = "divider") { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            items(rows, key = { it.key }) { row ->
                ReorderableItem(reorder, row.key, enabled = isTarget(row.key)) { dragging ->
                    when (row) {
                        is HomeLayout.Row.Group -> {
                            val group = groups[row.groupId] ?: return@ReorderableItem
                            Surface(shadowElevation = if (dragging) 4.dp else 0.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (group.hidden) "${group.title} (verborgen)" else group.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = deckColor(group.color, MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f),
                                    )
                                    DragHandle(
                                        onDragStarted = { draggedGroup = group.id; dragged = row.key },
                                        onDragStopped = { draggedGroup = null; dragged = null },
                                    )
                                }
                            }
                        }
                        is HomeLayout.Row.Loose, is HomeLayout.Row.GroupList -> {
                            val id = if (row is HomeLayout.Row.Loose) row.listId else (row as HomeLayout.Row.GroupList).listId
                            val list = lists[id] ?: return@ReorderableItem
                            val group = groups[list.groupId] ?: return@ReorderableItem
                            val pinned = layout.isPinned(id)
                            ArrangeListRow(list, group, subtitle = null, dragging, pinned, draggable = row !is HomeLayout.Row.GroupList || row.canReorder,
                                onPin = { layout = layout.pin(id, !pinned) }, onDragging = onDragging(row.key))
                        }
                        is HomeLayout.Row.Pinned -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ReorderableCollectionItemScope.ArrangeListRow(
    list: ListWithCount,
    group: GroupEntity,
    subtitle: String?,
    dragging: Boolean,
    pinned: Boolean,
    draggable: Boolean,
    onPin: () -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    Surface(shadowElevation = if (dragging) 4.dp else 0.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, tint = deckColor(group.color, MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPin) {
                Icon(
                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "„${list.title}” losmaken" else "„${list.title}” vastpinnen",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Deck lets only those who manage a board reorder its stacks.
            if (draggable) DragHandle(onDragStarted = { onDragging(true) }, onDragStopped = { onDragging(false) }) else Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.DragHandle(onDragStarted: () -> Unit = {}, onDragStopped: () -> Unit = {}) {
    // Not an IconButton: its click handling would take the press the drag starts with.
    Box(
        modifier = Modifier.size(48.dp).draggableHandle(onDragStarted = { onDragStarted() }, onDragStopped = onDragStopped),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragHandle, contentDescription = "Slepen om te verplaatsen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
