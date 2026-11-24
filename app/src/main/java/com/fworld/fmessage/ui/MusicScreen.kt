@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.music.MusicController
import com.fworld.fmessage.music.Track
import kotlinx.coroutines.launch

/** Keeps the YouTube player WebView alive (tiny, but attached so audio keeps playing). */
@Composable
fun MusicHost(vm: AppVM) {
    AndroidView(
        factory = { ctx ->
            vm.music.createWebView(ctx).also { (it.parent as? ViewGroup)?.removeView(it) }
        },
        modifier = Modifier.size(2.dp),
    )
}

@Composable
fun MiniPlayer(vm: AppVM) {
    val cur by vm.music.current.collectAsState()
    val playing by vm.music.playing.collectAsState()
    val t = cur ?: return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 4.dp,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = t.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.music.prev() }) { Ico(R.drawable.ic_skip_next, Modifier.graphicsLayer { rotationZ = 180f }, size = 22.dp) }
            FilledIconButton(onClick = { vm.music.toggle() }) { Ico(if (playing) R.drawable.ic_pause else R.drawable.ic_play, tint = MaterialTheme.colorScheme.onPrimary) }
            IconButton(onClick = { vm.music.next() }) { Ico(R.drawable.ic_skip_next) }
            IconButton(onClick = { vm.music.stop() }) { Ico(R.drawable.ic_close, size = 20.dp) }
        }
    }
}

@Composable
fun MusicScreen(vm: AppVM) {
    val saved by vm.music.saved.collectAsState()
    val cur by vm.music.current.collectAsState()
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    fun search(query: String) {
        if (query.isBlank()) return
        tab = 0
        scope.launch {
            busy = true
            results = vm.music.search(query.trim())
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Музыка", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Исполнитель или трек") },
            leadingIcon = { Ico(R.drawable.ic_search) },
            trailingIcon = { IconButton(onClick = { search(q) }) { Ico(R.drawable.ic_check) } },
            shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicController.GENRES.forEach { g -> AssistChip(onClick = { q = g; search(g) }, label = { Text(g) }) }
        }
        SecondaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Поиск") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Моя музыка (${saved.size})") })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = if (tab == 0) results.orEmpty() else saved
        if (list.isEmpty() && !busy) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(if (tab == 0) "Найдите любимую музыку" else "Сохранённых треков пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
            items(list, key = { it.id }) { t ->
                ListItem(
                    modifier = Modifier.clickable { vm.music.play(t, list) },
                    colors = ListItemDefaults.colors(containerColor = if (cur?.id == t.id) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { AsyncImage(model = t.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) },
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { vm.music.toggleSaved(t) }) {
                            Ico(R.drawable.ic_star, tint = if (vm.music.isSaved(t)) androidx.compose.ui.graphics.Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline)
                        }
                    },
                )
            }
        }
    }
}
