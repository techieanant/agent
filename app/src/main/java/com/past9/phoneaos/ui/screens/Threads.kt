package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.data.ChatThread
import com.past9.phoneaos.ui.theme.Eyebrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row of the thread list: title, snippet, time, unread badge, pin marker, live work marker. */
data class ThreadRow(
    val thread: ChatThread,
    val snippet: String = "",
    val unread: Int = 0,
    val active: Boolean = false,
    val working: Boolean = false,
    val workingLabel: String = "",
)
/** Multi-chat thread list (Muse-style threads sheet). New chat on top, archived at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsSheet(
    rows: List<ThreadRow>,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("CHATS", style = Eyebrow, color = cs.onSurfaceVariant)
            Text("One topic each", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Surface(onClick = onNew, shape = MaterialTheme.shapes.large, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = cs.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("Start a new chat", style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                    Text("empty", style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(8.dp))
            val (live, archived) = rows.partition { !it.thread.archived }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(live, key = { it.thread.id }) { r -> ThreadItem(r, onPick, onRename, onPin, onArchive, onDelete) }
                if (archived.isNotEmpty()) {
                    item(key = "archived-head") { Text("ARCHIVED", style = Eyebrow, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    items(archived, key = { it.thread.id }) { r -> ThreadItem(r, onPick, onRename, onPin, onArchive, onDelete) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadItem(
    r: ThreadRow,
    onPick: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val t = r.thread
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(t.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        onClick = { onPick(t.id) },
        shape = MaterialTheme.shapes.large,
        color = if (r.active) cs.secondaryContainer else cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.pinned) { Icon(Icons.Rounded.PushPin, null, Modifier.size(14.dp), tint = cs.primary); Spacer(Modifier.width(4.dp)) }
                    if (r.working && !r.active) { Icon(Icons.Rounded.Sync, "Working in this chat", Modifier.size(14.dp), tint = cs.tertiary); Spacer(Modifier.width(4.dp)) }
                    Text(t.title.ifBlank { "Chat" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.unread > 0 && !r.active) {
                        Spacer(Modifier.width(8.dp))
                        Badge { Text(if (r.unread > 9) "9+" else r.unread.toString()) }
                    }
                }
                if (r.snippet.isNotBlank()) Text(r.snippet, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.working && !r.active && r.workingLabel.isNotBlank()) Text(r.workingLabel, style = MaterialTheme.typography.labelSmall, color = cs.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(threadTime(t.updatedAt), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Chat options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; name = t.title; renaming = true })
                    DropdownMenuItem(text = { Text(if (t.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin(t.id, !t.pinned) })
                    if (t.id != "main") DropdownMenuItem(text = { Text(if (t.archived) "Restore" else "Archive") }, onClick = { menu = false; onArchive(t.id, !t.archived) })
                    if (t.id != "main") DropdownMenuItem(text = { Text("Delete", color = cs.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (renaming) ModalBottomSheet(onDismissRequest = { renaming = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Rename chat", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { renaming = false; onRename(t.id, name) }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
    if (confirmDelete) ModalBottomSheet(onDismissRequest = { confirmDelete = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Delete this chat?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("“${t.title}” and its whole history go away. This can't be undone.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { confirmDelete = false; onDelete(t.id) }, colors = ButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Delete") }
            }
        }
    }
}

private fun threadTime(at: Long): String {
    val dt = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val now = java.time.LocalDate.now()
    return when (dt.toLocalDate()) {
        now -> dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        now.minusDays(1) -> "Yesterday"
        else -> dt.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}
