@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.fworld.fmessage.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fworld.fmessage.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Ico(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Icon(painterResource(id), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

fun initials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

fun avatarShape(kind: String, size: Dp): Shape = when (kind) {
    "square" -> RoundedCornerShape(size * 0.1f)
    "squircle", "rounded" -> RoundedCornerShape(size * 0.34f)
    else -> CircleShape
}

@Composable
fun Avatar(
    name: String,
    colorHex: String,
    url: String,
    size: Dp = 48.dp,
    shapeKind: String = "circle",
    online: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = avatarShape(shapeKind, size)
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(shape).background(colorOf(colorHex)),
            contentAlignment = Alignment.Center,
        ) {
            if (url.isNotBlank()) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
            }
        }
        if (online) {
            Box(
                Modifier.size(size * 0.28f).align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
                    .background(Color(0xFF22C55E), CircleShape),
            )
        }
    }
}

@Composable
fun TierChip(tier: String) {
    if (tier == "free") return
    val c = if (tier == "fpremium") Color(0xFFA855F7) else Color(0xFFF59E0B)
    Surface(shape = RoundedCornerShape(8.dp), color = c.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(com.fworld.fmessage.R.drawable.ic_crown, tint = c, size = 12.dp)
            Spacer(Modifier.width(3.dp))
            Text(Plans.label(tier), color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CustomBadgeChip(p: Rec?) {
    val text = p?.s("custom_badge").orEmpty()
    if (text.isBlank()) return
    val c = colorOf(p?.s("custom_badge_color"))
    val shape = when (p?.s("custom_badge_shape")) { "circle" -> CircleShape; "square" -> RoundedCornerShape(4.dp); else -> RoundedCornerShape(10.dp) }
    Surface(shape = shape, color = c.copy(alpha = 0.2f)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = p?.s("custom_badge_icon").orEmpty()
            if (icon.isNotBlank() && icon.length <= 4) { Text(icon, fontSize = 11.sp); Spacer(Modifier.width(3.dp)) }
            Text(text, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Да", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun PromptDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) { onOk(v.trim()); onDismiss() } }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------- time ----------

private val zone get() = ZoneId.systemDefault()

fun fmtClock(ms: Long, h24: Boolean = true): String {
    if (ms <= 0) return ""
    val f = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a")
    return Instant.ofEpochMilli(ms).atZone(zone).format(f)
}

fun fmtChatTime(iso: String, h24: Boolean = true): String {
    val ms = parseIso(iso)
    if (ms <= 0) return ""
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d == today -> fmtClock(ms, h24)
        d == today.minusDays(1) -> "Вчера"
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMM"))
        else -> d.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

fun fmtDay(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMMM" else "d MMMM yyyy"))
    }
}

fun sameDay(a: Long, b: Long): Boolean =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

fun lastSeenText(p: Rec?): String {
    if (p == null) return ""
    if (profileOnline(p)) return "в сети"
    if (!p.b("show_activity_status", true)) return "был(а) недавно"
    val ms = parseIso(p.s("last_seen"))
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "был(а) только что"
        diff < 3_600_000 -> "был(а) ${diff / 60_000} мин назад"
        diff < 86_400_000 -> "был(а) ${diff / 3_600_000} ч назад"
        else -> "был(а) " + Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

fun fmtDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
