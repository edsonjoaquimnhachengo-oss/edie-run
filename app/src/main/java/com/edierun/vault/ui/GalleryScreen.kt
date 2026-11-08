package com.edierun.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edierun.vault.vault.FileUtil
import com.edierun.vault.vault.Kind
import com.edierun.vault.vault.Session
import com.edierun.vault.vault.Vault
import com.edierun.vault.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(vault: Vault, onOpen: (VaultItem) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vault.items.collectAsState()
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var originals by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VaultItem?>(null) }
    var exportTarget by remember { mutableStateOf<VaultItem?>(null) }

    val filtered = remember(items, filter) {
        when (filter) {
            "VIDEO" -> items.filter { it.kind == Kind.VIDEO }
            "IMAGE" -> items.filter { it.kind == Kind.IMAGE }
            "AUDIO" -> items.filter { it.kind == Kind.AUDIO }
            "PAGE" -> items.filter { it.kind == Kind.PAGE }
            else -> items
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = mutableListOf<Uri>()
            var failed = 0
            uris.forEachIndexed { i, uri ->
                importStatus = "A cifrar ${i + 1} de ${uris.size}…"
                try {
                    withContext(Dispatchers.IO) { importUri(ctx, vault, uri) }
                    ok += uri
                } catch (_: Throwable) {
                    failed++
                }
            }
            importStatus = null
            val msg = "${ok.size} ficheiro(s) guardado(s) no cofre" + if (failed > 0) ", $failed falharam" else ""
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            if (ok.isNotEmpty()) originals = ok
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = exportTarget
        exportTarget = null
        if (uri != null && item != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { vault.exportTo(item, it) } != null
                    } catch (_: Throwable) {
                        false
                    }
                }
                Toast.makeText(ctx, if (ok) "Exportado. Atenção: essa cópia está fora do cofre e sem cifra." else "Não foi possível exportar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            TopAppBar(
                title = { Text("Cofre · ${items.size}") },
                actions = {
                    IconButton(onClick = {
                        Session.allowLeaving()
                        picker.launch(arrayOf("image/*", "video/*", "audio/*"))
                    }) { Icon(Icons.Filled.Add, contentDescription = "Importar") }
                }
            )
        } else {
            TopAppBar(
                title = { Text("${selection.size} seleccionado(s)") },
                navigationIcon = {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar") }
                },
                actions = {
                    if (selection.size == 1) {
                        val one = items.firstOrNull { it.id in selection }
                        IconButton(onClick = { renameTarget = one }) { Icon(Icons.Filled.Edit, contentDescription = "Mudar o nome") }
                        IconButton(onClick = {
                            if (one != null) {
                                exportTarget = one
                                Session.allowLeaving()
                                exporter.launch(one.name)
                            }
                        }) { Icon(Icons.Filled.Share, contentDescription = "Exportar") }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Apagar") }
                }
            )
        }

        importStatus?.let {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(it, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "Tudo", "VIDEO" to "Vídeos", "IMAGE" to "Imagens", "AUDIO" to "Áudio", "PAGE" to "Páginas").forEach { (k, label) ->
                FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) })
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nada aqui ainda.\n\nToque em + para trazer fotos e vídeos do telemóvel, " +
                        "ou use o separador Downloads para guardar vídeos da internet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.id }) { item ->
                    val selected = item.id in selection
                    Thumb(
                        vault = vault,
                        item = item,
                        selected = selected,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (selected) selection - item.id else selection + item.id
                                } else {
                                    onOpen(item)
                                }
                            },
                            onLongClick = {
                                selection = if (selected) selection - item.id else selection + item.id
                            }
                        )
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar ${selection.size} ficheiro(s)?") },
            text = { Text("Saem do cofre para sempre. Não há reciclagem.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selection
                    confirmDelete = false
                    selection = emptySet()
                    scope.launch(Dispatchers.IO) { vault.delete(ids) }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Mudar o nome") },
            text = { PrivateTextField(newName, { newName = it }, "Nome") },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName
                    renameTarget = null
                    selection = emptySet()
                    scope.launch(Dispatchers.IO) { vault.rename(target, n) }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } }
        )
    }

    if (originals.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { originals = emptyList() },
            title = { Text("Apagar os originais?") },
            text = {
                Text(
                    "As cópias cifradas já estão no cofre. Quer apagar os ${originals.size} ficheiro(s) " +
                        "originais da galeria do telemóvel?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = originals
                    originals = emptyList()
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            list.count { uri ->
                                try {
                                    !DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                                } catch (_: Throwable) {
                                    true
                                }
                            }
                        }
                        val msg = if (failed == 0) "Originais apagados."
                        else "$failed original(is) não puderam ser apagados por aqui. Apague-os na galeria."
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Apagar originais") }
            },
            dismissButton = { TextButton(onClick = { originals = emptyList() }) { Text("Manter") } }
        )
    }
}

@Composable
private fun Thumb(vault: Vault, item: VaultItem, selected: Boolean, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { vault.thumbnail(item) }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                item.name.substringAfterLast('.', "?").uppercase().take(5),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp
            )
        }
        if (item.kind == Kind.VIDEO || item.kind == Kind.AUDIO || item.kind == Kind.PAGE) {
            Text(
                when (item.kind) {
                    Kind.VIDEO -> "▶"
                    Kind.AUDIO -> "♪"
                    else -> "WEB"
                },
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Text(
            item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x337FB8FF)))
        }
    }
}

private fun importUri(ctx: Context, vault: Vault, uri: Uri) {
    val resolver = ctx.contentResolver
    var name = "ficheiro"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { name = it }
        }
    }
    val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: FileUtil.guessMime(name)
    val input = resolver.openInputStream(uri) ?: throw java.io.IOException("não foi possível abrir")
    input.use { vault.importStream(name, mime, it) }
}
