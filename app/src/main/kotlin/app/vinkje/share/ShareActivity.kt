package app.vinkje.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.vinkje.MainActivity
import app.vinkje.TodoApp
import app.vinkje.data.TodoRepository
import app.vinkje.domain.SharedTask
import app.vinkje.domain.SharedText
import app.vinkje.domain.TaskBody
import app.vinkje.domain.TaskBodyCodec
import app.vinkje.ui.TodoTheme

/**
 * "Taak toevoegen" in Android's share menu: a sheet over the app shared from, with the
 * title and a list to put it on. The task is saved locally at once and synced later, as
 * everywhere else, so this works offline and the sheet closes without waiting.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = SharedText.toTask(
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        )
        if (shared == null) {
            Toast.makeText(applicationContext, "Niets om toe te voegen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = application as TodoApp
        setContent {
            TodoTheme {
                val credentials by app.credentials.collectAsStateWithLifecycle()
                ShareSheet(
                    repo = app.repository,
                    shared = shared,
                    loggedIn = credentials != null,
                    onAdded = { list ->
                        Toast.makeText(applicationContext, "Toegevoegd aan $list", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }
}

/** A line in the list picker: a group's heading or a list to pick. */
private sealed interface PickerRow {
    data class Header(val groupId: Long, val title: String) : PickerRow
    data class Entry(val id: Long, val title: String) : PickerRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    repo: TodoRepository,
    shared: SharedTask,
    loggedIn: Boolean,
    onAdded: (listTitle: String) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Taak toevoegen", style = MaterialTheme.typography.titleLarge)
            if (loggedIn) {
                AddForm(repo, shared, onAdded, onOpenApp)
            } else {
                Text("Log eerst in in Vinkje.")
                OpenAppButton(onOpenApp)
            }
        }
    }
}

@Composable
private fun ColumnScope.AddForm(repo: TodoRepository, shared: SharedTask, onAdded: (String) -> Unit, onOpenApp: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lists by repo.writableLists().collectAsStateWithLifecycle(null)
    val groups by repo.groups().collectAsStateWithLifecycle(emptyList())
    var title by rememberSaveable { mutableStateOf(shared.title) }
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(lists) {
        val current = lists ?: return@LaunchedEffect
        if (current.none { it.id == picked }) picked = repo.defaultListId()
    }

    // The lists outside a group first, without a heading, as on the home screen; then each group.
    val rows = remember(lists, groups) {
        val byId = groups.associateBy { it.id }
        lists.orEmpty()
            .groupBy { it.groupId }.entries
            .sortedBy { (groupId, _) -> byId[groupId]?.let { !repo.isLoose(it) } ?: true }
            .flatMap { (groupId, inGroup) ->
                val group = byId[groupId]
                val header = group?.takeUnless(repo::isLoose)?.let { listOf(PickerRow.Header(it.id, it.title)) }.orEmpty()
                header + inGroup.map { PickerRow.Entry(it.id, it.title) }
            }
    }

    fun add() {
        val listId = picked ?: return
        if (title.isBlank() || adding) return
        adding = true
        val listTitle = lists?.firstOrNull { it.id == listId }?.title ?: ""
        scope.launch {
            // Swiping the sheet away must not stop it between saving the task and queueing its sync.
            withContext(NonCancellable) {
                repo.addTask(listId, title, description = TaskBodyCodec.format(TaskBody(note = shared.note)))
                repo.setDefaultList(listId)
            }
            onAdded(listTitle)
        }
    }

    OutlinedTextField(
        value = title,
        onValueChange = { title = it.take(SharedText.MAX_TITLE) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Titel") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
    )
    if (shared.note.isNotBlank()) {
        Text(
            shared.note.lines().first { it.isNotBlank() }.trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when {
        lists == null -> {}
        rows.isEmpty() -> {
            Text("Nog geen lijsten.")
            OpenAppButton(onOpenApp)
        }
        else -> {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                items(rows, key = { if (it is PickerRow.Entry) it.id else "group:${(it as PickerRow.Header).groupId}" }) { row ->
                    when (row) {
                        is PickerRow.Header -> Text(
                            row.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        is PickerRow.Entry -> Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = row.id == picked, role = Role.RadioButton, onClick = { picked = row.id })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = row.id == picked, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Button(onClick = ::add, enabled = picked != null && title.isNotBlank() && !adding, modifier = Modifier.align(Alignment.End)) {
                Text("Toevoegen")
            }
        }
    }
}

@Composable
private fun OpenAppButton(onOpenApp: () -> Unit) = TextButton(onClick = onOpenApp) { Text("Vinkje openen") }
