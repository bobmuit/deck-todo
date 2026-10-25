package app.vinkje.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.vinkje.R
import app.vinkje.data.GroupEntity
import app.vinkje.data.ListWithCount
import app.vinkje.data.TodoRepository

/** What the home screen is asking about in a dialog. */
private sealed interface HomeDialog {
    data object NewGroup : HomeDialog
    data class NewList(val groupId: Long?) : HomeDialog
    data class RenameGroup(val group: GroupEntity) : HomeDialog
    data class DeleteGroup(val group: GroupEntity) : HomeDialog
    data class Share(val groupId: Long) : HomeDialog
}

/**
 * The To Do sidebar as a screen: the smart lists, then the lists outside any group, then
 * the groups (Deck boards) with their lists (stacks). A tap on a group folds it; a long
 * press opens what can be done with it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repo: TodoRepository,
    onOpenList: (Long) -> Unit,
    onOpenSmart: (SmartList) -> Unit,
    loginExpired: Boolean,
    onLogInAgain: () -> Unit,
    onLogOut: () -> Unit,
) {
    val home by repo.home.collectAsStateWithLifecycle()
    val groups = home?.groups.orEmpty()
    val lists = home?.lists.orEmpty()
    val shared = home?.anyShared ?: false
    val pending by repo.pendingCount().collectAsStateWithLifecycle(0)
    val counts by repo.smartCounts().collectAsStateWithLifecycle(null)
    val self by repo.selfUid.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmLogOut by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hiddenCount = groups.count { it.hidden }
    val (loose, grouped) = groups.partition { repo.isLoose(it) }

    LaunchedEffect(Unit) { repo.syncNow() }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            repo.syncNow()
            delay(1200)
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (pending > 0) {
                        Icon(Icons.Outlined.CloudOff, contentDescription = "$pending wijzigingen nog niet gesynchroniseerd", modifier = Modifier.padding(end = 4.dp))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Meer") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem("Uitloggen") { menu = false; confirmLogOut = true }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { dialog = HomeDialog.NewList(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Nieuwe lijst", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    IconButton(onClick = { dialog = HomeDialog.NewGroup }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Nieuwe groep")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (loginExpired) LoginExpiredBanner(pending, onLogInAgain)
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true }) {
                // Until the rows are known, an empty list would reset the scroll position kept for it.
                if (home != null) LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // "Aan mij toegewezen" only means something once a group is shared.
                    items(SmartList.entries.filter { shared || it != SmartList.ASSIGNED }, key = { it.name }) { smart ->
                        val count = when (smart) {
                            SmartList.MY_DAY -> counts?.myDay
                            SmartList.IMPORTANT -> counts?.important
                            SmartList.PLANNED -> counts?.planned
                            SmartList.ASSIGNED -> counts?.assigned
                        } ?: 0
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenSmart(smart) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(smart.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(smart.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (count > 0) Text("$count", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "divider") { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    if (groups.isEmpty()) {
                        item {
                            Text(
                                "Nog geen lijsten. Trek omlaag om te synchroniseren, of maak hieronder een nieuwe lijst.",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    for (group in loose) {
                        items(lists.filter { it.groupId == group.id }, key = { "l${it.id}" }) { list -> ListRow(list, group, onOpenList) }
                    }
                    for (group in grouped.filter { showHidden || !it.hidden }) {
                        item(key = "g${group.id}") {
                            GroupHeader(
                                group = group,
                                actions = groupActions(group, self),
                                onToggle = { scope.launch { repo.setGroupCollapsed(group.id, !group.collapsed) } },
                                onAddList = { dialog = HomeDialog.NewList(group.id) },
                                onRename = { dialog = HomeDialog.RenameGroup(group) },
                                onShare = { dialog = HomeDialog.Share(group.id) },
                                onHide = { scope.launch { repo.setGroupHidden(group.id, !group.hidden) } },
                                onDelete = { dialog = HomeDialog.DeleteGroup(group) },
                            )
                        }
                        if (!group.collapsed) {
                            items(lists.filter { it.groupId == group.id }, key = { "l${it.id}" }) { list -> ListRow(list, group, onOpenList) }
                        }
                    }
                    if (hiddenCount > 0) {
                        item(key = "hidden") {
                            TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.padding(8.dp)) {
                                Text(if (showHidden) "Verborgen groepen weer verbergen" else "Verborgen groepen tonen ($hiddenCount)")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmLogOut) {
        ConfirmDialog(
            title = "Uitloggen?",
            text = "Je lijsten verdwijnen van deze telefoon; in Nextcloud blijven ze bewaard." +
                (if (pending > 0) " Let op: $pending wijziging${if (pending == 1) "" else "en"} heeft Nextcloud nog niet bereikt en gaat verloren." else ""),
            confirm = "Uitloggen",
            onDismiss = { confirmLogOut = false },
        ) {
            confirmLogOut = false
            onLogOut()
        }
    }

    when (val d = dialog) {
        null -> {}
        HomeDialog.NewGroup -> NameDialog("Nieuwe groep", confirm = "Maken", onDismiss = { dialog = null }) { title ->
            dialog = null
            scope.launch { repo.createGroup(title) }
        }
        is HomeDialog.NewList -> NameDialog("Nieuwe lijst", confirm = "Maken", onDismiss = { dialog = null }) { title ->
            dialog = null
            scope.launch { onOpenList(repo.createList(title, d.groupId)) }
        }
        is HomeDialog.RenameGroup -> NameDialog("Groep hernoemen", initial = d.group.title, onDismiss = { dialog = null }) { title ->
            dialog = null
            scope.launch { repo.renameGroup(d.group.id, title) }
        }
        is HomeDialog.DeleteGroup -> ConfirmDialog(
            title = "Groep verwijderen?",
            text = "„${d.group.title}” en alle lijsten erin worden verwijderd" +
                (if (d.group.sharedWith.isNotEmpty()) ", ook voor ${d.group.sharedWith}." else "."),
            confirm = "Verwijderen",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch { repo.deleteGroup(d.group.id) }
        }
        is HomeDialog.Share -> ShareDialog(repo, d.groupId, onDismiss = { dialog = null })
    }
}

@Composable
private fun LoginExpiredBanner(pending: Int, onLogInAgain: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Nextcloud laat Vinkje niet meer binnen. Log opnieuw in om weer te synchroniseren." +
                    if (pending > 0) " Je wijzigingen blijven tot dan op deze telefoon bewaard." else "",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onLogInAgain, modifier = Modifier.padding(top = 8.dp)) { Text("Opnieuw inloggen") }
        }
    }
}

@Composable
private fun ListRow(list: ListWithCount, group: GroupEntity, onOpenList: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenList(list.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, tint = deckColor(group.color, MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(16.dp))
        Text(list.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (list.openCount > 0) Text("${list.openCount}", style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: GroupEntity,
    actions: GroupActions,
    onToggle: () -> Unit,
    onAddList: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = { menu = true }, onLongClickLabel = "Groep bewerken")
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (group.hidden) "${group.title} (verborgen)" else group.title,
                style = MaterialTheme.typography.titleSmall,
                color = deckColor(group.color, MaterialTheme.colorScheme.primary),
            )
            if (group.sharedWith.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.People, contentDescription = "Gedeeld met ${group.sharedWith}", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (group.collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = if (group.collapsed) "Uitklappen" else "Inklappen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val close = { action: () -> Unit -> { menu = false; action() } }
            if (actions.addList) MenuItem("Nieuwe lijst in deze groep", close(onAddList))
            if (actions.rename) MenuItem("Naam wijzigen", close(onRename))
            if (actions.share) MenuItem(if (group.sharedWith.isEmpty()) "Delen" else "Delen met…", close(onShare))
            MenuItem(if (group.hidden) "Weer tonen op deze telefoon" else "Verbergen op deze telefoon", close(onHide))
            if (actions.delete) MenuItem("Groep verwijderen", close(onDelete))
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = onClick)
