@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.music.MusicController
import com.fworld.fmessage.music.Track

@Composable
fun TrackArt(t: Track?, size: Dp, radius: Dp = 14.dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(scheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Ico(R.drawable.ic_music_note, tint = scheme.onTertiaryContainer, size = size * 0.42f)
        if (t != null && t.artwork.isNotBlank()) {
            AsyncImage(model = t.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MiniPlayer(vm: AppVM) {
    val cur by vm.music.current.collectAsState()
    val playing by vm.music.playing.collectAsState()
    val pos by vm.music.position.collectAsState()
    val dur by vm.music.duration.collectAsState()
    var open by remember { mutableStateOf(false) }
    val t = cur ?: return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable { open = true },
        shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp, shadowElevation = 10.dp,
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArt(t, 42.dp, 14.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                }
                FilledIconButton(onClick = { vm.music.toggle() }, modifier = Modifier.size(40.dp)) {
                    Ico(if (playing) R.drawable.ic_pause_filled else R.drawable.ic_play_filled, tint = MaterialTheme.colorScheme.onPrimary, size = 20.dp)
                }
                IconButton(onClick = { vm.music.next() }) { Ico(R.drawable.ic_skip_next, size = 22.dp) }
            }
            LinearProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                trackColor = Color.Transparent,
            )
        }
    }
    if (open) NowPlayingSheet(vm, onDismiss = { open = false })
}

@Composable
fun NowPlayingSheet(vm: AppVM, onDismiss: () -> Unit) {
    val music = vm.music
    val cur by music.current.collectAsState()
    val playing by music.playing.collectAsState()
    val pos by music.position.collectAsState()
    val dur by music.duration.collectAsState()
    val shuffle by music.shuffle.collectAsState()
    val repeat by music.repeat.collectAsState()
    val saved by music.saved.collectAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val radius by animateDpAsState(if (playing) 28.dp else 44.dp, label = "playShape")
    val t = cur
    if (t == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TrackArt(t, 280.dp, 36.dp)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { music.toggleSaved(t) }) {
                    val fav = saved.any { it.id == t.id }
                    Ico(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart, tint = if (fav) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Slider(
                value = drag ?: pos.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { music.seekTo(it.toLong()) }; drag = null },
                valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtDuration(((drag ?: pos.toFloat()) / 1000).toInt()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(fmtDuration((dur / 1000).toInt()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { music.toggleShuffle() }) {
                    Ico(R.drawable.ic_shuffle, tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { music.prev() }, modifier = Modifier.size(52.dp)) { Ico(R.drawable.ic_skip_prev, size = 28.dp) }
                FilledIconButton(
                    onClick = { music.toggle() }, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(radius),
                ) { Ico(if (playing) R.drawable.ic_pause_filled else R.drawable.ic_play_filled, tint = MaterialTheme.colorScheme.onPrimary, size = 34.dp) }
                IconButton(onClick = { music.next() }, modifier = Modifier.size(52.dp)) { Ico(R.drawable.ic_skip_next, size = 28.dp) }
                IconButton(onClick = { music.cycleRepeat() }) {
                    Ico(
                        if (repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                        tint = if (repeat != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MusicScreen(vm: AppVM) {
    val music = vm.music
    val library by music.library.collectAsState()
    val saved by music.saved.collectAsState()
    val scanning by music.scanning.collectAsState()
    val cur by music.current.collectAsState()
    val playing by music.playing.collectAsState()
    var granted by remember { mutableStateOf(music.hasPermission()) }
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) music.scan() else vm.toast("Нужен доступ к музыке на устройстве")
    }
    LaunchedEffect(granted) { if (granted && library.isEmpty()) music.scan() }

    val source = if (tab == 0) library else saved
    val list = remember(source, q) {
        val s = q.trim().lowercase()
        if (s.isEmpty()) source else source.filter { (it.title + " " + it.artist + " " + it.album).lowercase().contains(s) }
    }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Музыка", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                if (granted) Text("${library.size} треков на устройстве", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            if (granted) IconButton(onClick = { music.scan() }) {
                if (scanning) LoadingIndicator(Modifier.size(28.dp)) else Ico(R.drawable.ic_refresh)
            }
        }

        if (!granted) {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(36.dp)).background(scheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                    Ico(R.drawable.ic_music_note, size = 48.dp, tint = scheme.onTertiaryContainer)
                }
                Spacer(Modifier.height(20.dp))
                Text("Ваша музыка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Разрешите доступ, чтобы приложение нашло треки на этом устройстве. Файлы никуда не отправляются.",
                    textAlign = TextAlign.Center, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { launcher.launch(MusicController.permission()) }, modifier = Modifier.height(52.dp)) { Text("Разрешить доступ") }
            }
            return@Column
        }

        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Название, исполнитель, альбом") },
            leadingIcon = { Ico(R.drawable.ic_search) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Ico(R.drawable.ic_close) } },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { music.playAll(list, false) }, enabled = list.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                Ico(R.drawable.ic_play_filled, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("Слушать")
            }
            FilledTonalButton(onClick = { music.playAll(list, true) }, enabled = list.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                Ico(R.drawable.ic_shuffle, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("Перемешать")
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Все треки") }, shape = RoundedCornerShape(14.dp))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Избранное (${saved.size})") }, shape = RoundedCornerShape(14.dp))
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        scanning -> "Сканируем музыку…"
                        tab == 1 -> "Избранных треков пока нет"
                        else -> "Треки не найдены"
                    },
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
            items(list, key = { it.id }) { t ->
                val isCur = cur?.id == t.id
                ListItem(
                    modifier = Modifier.clickable { music.play(t, list) },
                    colors = ListItemDefaults.colors(containerColor = if (isCur) scheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent),
                    leadingContent = {
                        Box(contentAlignment = Alignment.Center) {
                            TrackArt(t, 52.dp, 14.dp)
                            if (isCur) Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                Ico(if (playing) R.drawable.ic_pause_filled else R.drawable.ic_play_filled, tint = Color.White, size = 22.dp)
                            }
                        }
                    },
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium) },
                    supportingContent = { Text(t.artist + " · " + fmtDuration((t.durationMs / 1000).toInt()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { music.toggleSaved(t) }) {
                            val fav = music.isSaved(t)
                            Ico(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart, tint = if (fav) Color(0xFFEF4444) else scheme.outline, size = 22.dp)
                        }
                    },
                )
            }
        }
    }
}
