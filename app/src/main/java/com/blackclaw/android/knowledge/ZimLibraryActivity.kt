package com.blackclaw.android.knowledge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.assist.QuickAssistActivity
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.onboarding.PermissionExplanationDialog
import com.blackclaw.android.ui.onboarding.PermissionTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Native, offline-first ZIM library. It never launches or depends on Kiwix. */
class ZimLibraryActivity : BaseActivity() {
    data class Library(val file: File, val info: String, val indexed: Boolean)
    data class Hit(val title: String, val path: String, val snippet: String, val inContent: Boolean)

    private var libraries by mutableStateOf<List<Library>>(emptyList())
    private var selected by mutableStateOf<Library?>(null)
    private var hits by mutableStateOf<List<Hit>>(emptyList())
    private var article by mutableStateOf<DirectZimReader.Article?>(null)
    private var query by mutableStateOf("")
    private var message by mutableStateOf("Buscando bibliotecas…")
    private var loading by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val permissionColors = with(ThemeManager) { ThemeManager.getColors().toComposeColors() }
        setContent {
            var showStorageEducation by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                ZimLibraryScreen(
                    libraries, selected, hits, article, query, message, loading,
                    onBack = { if (article != null) article = null else if (selected != null) select(null) else finish() },
                    onRefresh = ::loadLibraries,
                    onGrantStorage = { showStorageEducation = true },
                    onSelect = { select(it) },
                    onQueryChange = { query = it },
                    onSearch = ::search,
                    onOpenArticle = ::readArticle,
                    onAsk = ::askBlackClaw,
                    onIndex = ::startIndex,
                )
                if (showStorageEducation) {
                    PermissionExplanationDialog(
                        topic = PermissionTopic.FILES,
                        colors = permissionColors,
                        onDismiss = { showStorageEducation = false },
                        onContinue = {
                            showStorageEducation = false
                            openStorageAccess()
                        },
                    )
                }
            }
        }
        loadLibraries()
    }

    override fun onResume() {
        super.onResume()
        if (libraries.isEmpty() && !loading) loadLibraries()
    }

    private fun loadLibraries() {
        loading = true
        message = "Buscando archivos .zim…"
        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) { DirectZimLibrary.scan() }
            val scanResult = withContext(Dispatchers.IO) {
                val loaded = mutableListOf<Library>()
                val failures = mutableListOf<String>()
                scan.archives.forEach { file ->
                    runCatching {
                        DirectZimReader(file).use { reader ->
                            Library(file, reader.libraryInfo(), ZimContentIndex.exists(this@ZimLibraryActivity, file))
                        }
                    }.onSuccess(loaded::add).onFailure { error ->
                        // Do not label every failure as an incomplete download: the
                        // actual reason distinguishes permissions, codecs and damage.
                        failures += "${file.name}: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
                loaded to failures
            }
            val loaded = scanResult.first
            val failures = scanResult.second
            libraries = loaded
            loading = false
            message = when {
                // An archive that was found but could not be opened is a different
                // problem from not finding one, and saying "none found" would send the
                // user looking for a file that is right there.
                loaded.isEmpty() && scan.archives.isNotEmpty() ->
                    "Encontré ${scan.archives.size} archivo(s) .zim pero no pude abrirlos. " +
                        failures.take(2).joinToString(" · ")
                loaded.isEmpty() ->
                    ZimDiscovery.explainEmptyResult(scan.hasFullStorageAccess, scan.splitPartNames)
                loaded.size == 1 -> "1 biblioteca offline disponible"
                else -> "${loaded.size} bibliotecas offline disponibles"
            }
        }
    }

    private fun select(library: Library?) {
        selected = library
        article = null
        hits = emptyList()
        query = ""
        message = if (library == null) "${libraries.size} bibliotecas offline disponibles" else "Busca un artículo o tema"
    }

    private fun search() {
        val library = selected ?: return
        val requested = query.trim()
        if (requested.isBlank()) return
        loading = true
        message = "Buscando dentro de ${library.file.name}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val merged = LinkedHashMap<String, Hit>()
                    if (ZimContentIndex.exists(this@ZimLibraryActivity, library.file)) {
                        DirectZimReader(library.file).use { reader ->
                            ZimContentIndex.open(this@ZimLibraryActivity, library.file, reader.titleEntryCount).use { index ->
                                index.search(requested, 12).forEach {
                                    merged[it.path] = Hit(it.title, it.path, it.snippet, true)
                                }
                            }
                        }
                    }
                    DirectZimReader(library.file).use { reader ->
                        reader.searchTitles(requested, 12).forEach {
                            merged.putIfAbsent(it.path, Hit(it.title, it.path, "Coincidencia en el título", false))
                        }
                    }
                    merged.values.take(20)
                }
            }
            loading = false
            result.onSuccess {
                hits = it
                message = if (it.isEmpty()) "No encontré coincidencias. Prueba con el nombre principal del tema." else "${it.size} resultados"
            }.onFailure { message = "No pude buscar: ${it.message}" }
        }
    }

    private fun readArticle(hit: Hit) {
        val library = selected ?: return
        loading = true
        message = "Abriendo ${hit.title}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DirectZimReader(library.file).use { it.readArticle(hit.path, 100_000) } }
            }
            loading = false
            result.onSuccess { article = it; message = "Artículo offline" }
                .onFailure { message = "No pude abrir el artículo: ${it.message}" }
        }
    }

    private fun askBlackClaw() {
        val library = selected ?: return
        val topic = article?.title ?: query.trim()
        if (topic.isBlank()) return
        startActivity(Intent(this, QuickAssistActivity::class.java).apply {
            putExtra(QuickAssistActivity.EXTRA_COMMAND,
                "Consulta la biblioteca ZIM ${library.file.absolutePath} y explícame: $topic")
        })
    }

    private fun startIndex() {
        val library = selected ?: return
        if (ZimIndexService.start(ClawApplication.instance, library.file, false)) {
            message = "Creando índice de contenido en segundo plano. Puedes pausarlo desde la notificación."
        } else message = "Android no permitió iniciar el índice."
    }

    private fun openStorageAccess() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZimLibraryScreen(
    libraries: List<ZimLibraryActivity.Library>,
    selected: ZimLibraryActivity.Library?,
    hits: List<ZimLibraryActivity.Hit>,
    article: DirectZimReader.Article?,
    query: String,
    message: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGrantStorage: () -> Unit,
    onSelect: (ZimLibraryActivity.Library) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenArticle: (ZimLibraryActivity.Hit) -> Unit,
    onAsk: () -> Unit,
    onIndex: () -> Unit,
) {
    val purple = Color(0xFF9D7CFF)
    Scaffold(
        containerColor = Color(0xFF07050C),
        topBar = {
            TopAppBar(
                title = { Text(article?.title ?: selected?.file?.nameWithoutExtension ?: "Biblioteca offline",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Actualizar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17102A), Color(0xFF07050C))))
            .padding(padding)) {
            when {
                article != null -> ArticleView(article, selected, onAsk)
                selected != null -> LibraryView(selected, hits, query, message, loading, onQueryChange, onSearch, onOpenArticle, onAsk, onIndex)
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        GlassCard {
                            Text("Tu conocimiento, sin internet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("BlackClaw consulta sólo los artículos necesarios. Nunca carga Wikipedia completa en la memoria.",
                                color = Color(0xFFB8AEC8), fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = purple) }
                    items(libraries, key = { it.file.absolutePath }) { library ->
                        GlassCard(Modifier.clickable { onSelect(library) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = purple, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(library.file.nameWithoutExtension, color = Color.White, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(formatBytes(library.file.length()) + if (library.indexed) " · índice de contenido" else " · búsqueda por títulos",
                                        color = Color(0xFFAFA4C2), fontSize = 12.sp)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF8F83A5))
                            }
                        }
                    }
                    item {
                        Text(message, color = Color(0xFFB8AEC8), fontSize = 13.sp, modifier = Modifier.padding(6.dp))
                        if (libraries.isEmpty() && !loading) {
                            TextButton(onClick = onGrantStorage) { Text("Conceder acceso a archivos") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryView(
    library: ZimLibraryActivity.Library,
    hits: List<ZimLibraryActivity.Hit>,
    query: String,
    message: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (ZimLibraryActivity.Hit) -> Unit,
    onAsk: () -> Unit,
    onIndex: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(library.info, color = Color(0xFFAFA4C2), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Buscar artículo o tema…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { IconButton(onClick = onSearch) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Buscar") } },
                shape = RoundedCornerShape(18.dp),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onAsk, label = { Text("Preguntar a BlackClaw") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
                if (!library.indexed) AssistChip(onClick = onIndex, label = { Text("Indexar contenido") })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            Text(message, color = Color(0xFFAFA4C2), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
        items(hits, key = { it.path }) { hit ->
            GlassCard(Modifier.clickable { onOpen(hit) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape)
                        .background(if (hit.inContent) Color(0xFF55D6A0) else Color(0xFF9D7CFF)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(hit.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(hit.snippet, color = Color(0xFFB8AEC8), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(hit.path, color = Color(0xFF756B89), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ArticleView(article: DirectZimReader.Article, library: ZimLibraryActivity.Library?, onAsk: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text("${library?.file?.name.orEmpty()} · ${article.path}", color = Color(0xFF887D9C), fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            Text(article.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
            Spacer(Modifier.height(10.dp))
            AssistChip(onClick = onAsk, label = { Text("Explícamelo") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
            Spacer(Modifier.height(18.dp))
            Text(article.text, color = Color(0xFFD8D0E3), fontSize = 16.sp, lineHeight = 25.sp)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = Color(0xB31D1729), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    else -> "${bytes / 1024} KB"
}
