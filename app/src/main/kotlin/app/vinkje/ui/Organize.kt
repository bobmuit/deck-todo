package app.vinkje.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.vinkje.data.GroupEntity
import app.vinkje.data.Members
import app.vinkje.data.TodoRepository
import app.vinkje.deck.User

/*
 * Dialogs for organising: naming lists and groups, moving a list, sharing a group.
 */

/** Asks for a name, with the keyboard up and the current name selected. */
@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Opslaan", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(value.text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

/** Picks a group for a list: any other group whose lists may be changed, or none. */
@Composable
fun MoveListDialog(repo: TodoRepository, currentGroupId: Long, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val groups by repo.groups().collectAsStateWithLifecycle(emptyList())
    val current = groups.firstOrNull { it.id == currentGroupId }
    val targets = groups.filter { it.id != currentGroupId && it.canManage && !it.hidden && !repo.isLoose(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verplaatsen naar") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                if (current == null || !repo.isLoose(current)) {
                    item { PickRow(Icons.AutoMirrored.Outlined.List, "Geen groep") { onPick(null) } }
                }
                items(targets, key = { it.id }) { group -> PickRow(Icons.Outlined.Folder, group.title) { onPick(group.id) } }
                if (targets.isEmpty() && current != null && repo.isLoose(current)) {
                    item { Text("Er is nog geen groep. Maak er een op het startscherm.", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

@Composable
private fun PickRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Who a group is shared with, and finding someone to add. Sharing is per group (a Deck
 * board), so everyone added sees and edits every list in it.
 */
@Composable
fun ShareDialog(repo: TodoRepository, groupId: Long, onDismiss: () -> Unit) {
    val group by repo.group(groupId).collectAsStateWithLifecycle(null)
    val self by repo.selfUid.collectAsStateWithLifecycle()
    val g = group ?: return
    val members = Members.decode(g.members)
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<User>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        offline = false
        if (query.trim().length < 2) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(300) // typing on: this search is superseded
        runCatching { repo.findUsers(query.trim()) }
            .onSuccess { found = it }
            .onFailure { found = emptyList(); offline = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${g.title}” delen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Wie je toevoegt, ziet en bewerkt alle lijsten in deze groep.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((uid, name) in members) {
                    MemberRow(
                        name = name + when (uid) {
                            g.ownerUid -> " (eigenaar)"
                            self -> " (jij)"
                            else -> ""
                        },
                        onRemove = if (g.canShare && uid != g.ownerUid && uid != self) ({ scope.launch { repo.unshare(g.id, uid) } }) else null,
                    )
                }
                if (g.canShare) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Naam zoeken") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (offline) {
                        Text("Zoeken lukt alleen met verbinding met de server.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    for (user in found.filter { it.uid !in members }) {
                        PickRow(Icons.Outlined.PersonAddAlt, user.displayname) {
                            scope.launch { repo.share(g.id, user) }
                            query = ""
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Klaar") } },
    )
}

@Composable
private fun MemberRow(name: String, onRemove: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PersonOutline, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "$name niet meer delen") }
        }
    }
}

/** The group header's long-press menu items, by what this user may do there. */
data class GroupActions(val addList: Boolean, val rename: Boolean, val share: Boolean, val delete: Boolean)

fun groupActions(group: GroupEntity, self: String?) = GroupActions(
    addList = group.canManage,
    rename = group.canManage,
    share = group.canShare,
    delete = group.ownerUid == self,
)
