@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.*

private val ACCENTS = listOf("#6366f1", "#8b5cf6", "#ec4899", "#ef4444", "#f97316", "#22c55e", "#14b8a6", "#0ea5e9", "#64748b")

@Composable
private fun SwitchRow(vm: AppVM, me: Rec, title: String, key: String, default: Boolean = true, subtitle: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = me.b(key, default), onCheckedChange = { vm.updateMe(key to it) }) },
        modifier = Modifier.clickable { vm.updateMe(key to !me.b(key, default)) },
    )
}

@Composable
private fun ChoiceRow(vm: AppVM, me: Rec, title: String, key: String, options: List<Pair<String, String>>, default: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = me.s(key).ifBlank { default }
            options.forEach { (v, l) -> FilterChip(selected = cur == v, onClick = { vm.updateMe(key to v) }, label = { Text(l) }) }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVM) {
    val user by vm.user.collectAsState()
    val theme by vm.themeMode.collectAsState()
    val me = user ?: return
    var showPro by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        Text("Настройки", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // account card
        Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(me.displayName(), me.s("avatar_color"), me.s("avatar_url"), 56.dp, me.s("avatar_shape").ifBlank { "circle" })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me.displayName(), fontWeight = FontWeight.Bold)
                    Text(me.s("email"), fontSize = 13.sp)
                }
                TierChip(Plans.tier(me))
            }
        }

        SectionTitle("Внешний вид")
        Text("Тема", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (v, l) ->
                    FilterChip(selected = theme == v, onClick = { Prefs.themeMode = v; vm.themeMode.value = v }, label = { Text(l) })
                }
            }
        }
        ChoiceRow(vm, me, "Стиль цвета", "design_style", listOf("default" to "Акцентный", "material3" to "Material You"), "default")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Акцентный цвет")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ACCENTS.forEach { c ->
                    Box(Modifier.size(38.dp).background(colorOf(c), CircleShape).clickable { vm.updateMe("accent_color" to c) }, contentAlignment = Alignment.Center) {
                        if (me.s("accent_color").ifBlank { "#6366f1" }.equals(c, true)) Ico(R.drawable.ic_check, tint = Color.White, size = 20.dp)
                    }
                }
            }
        }
        ChoiceRow(vm, me, "Размер текста", "font_size", listOf("small" to "Мелкий", "medium" to "Средний", "large" to "Крупный", "xl" to "Очень крупный"), "medium")
        ChoiceRow(vm, me, "Шрифт", "font_family", listOf("system" to "Системный", "serif" to "С засечками", "mono" to "Моно", "rounded" to "Округлый"), "system")
        SwitchRow(vm, me, "Чёрная тема (OLED)", "dark_mode_oled", false)
        SwitchRow(vm, me, "Высокий контраст", "high_contrast", false)
        SwitchRow(vm, me, "Уменьшить анимации", "reduce_motion", false)

        SectionTitle("Чаты")
        SwitchRow(vm, me, "Отправка по Enter", "enter_to_send", true)
        SwitchRow(vm, me, "Время сообщений", "show_timestamps", true)
        ChoiceRow(vm, me, "Формат времени", "time_format", listOf("24h" to "24 ч", "12h" to "12 ч"), "24h")
        ChoiceRow(vm, me, "Плотность сообщений", "message_density", listOf("comfortable" to "Обычная", "compact" to "Компактная"), "comfortable")
        ChoiceRow(vm, me, "Стиль пузырей", "bubble_style", listOf("default" to "Стандартный", "round" to "Круглый", "sharp" to "Строгий"), "default")
        SwitchRow(vm, me, "Группировать сообщения", "message_grouping", true)
        SwitchRow(vm, me, "Лайк двойным нажатием", "double_tap_to_like", true)
        SwitchRow(vm, me, "Имена в группах", "show_sender_names", true)
        ChoiceRow(vm, me, "Сортировка чатов", "chat_sort_order", listOf("recent" to "По времени", "pinned_alpha" to "По алфавиту"), "recent")
        ChoiceRow(vm, me, "Плотность списка чатов", "chat_list_density", listOf("comfortable" to "Обычная", "compact" to "Компактная"), "comfortable")
        SwitchRow(vm, me, "Превью в списке чатов", "show_chat_preview", true)
        SwitchRow(vm, me, "Счётчик непрочитанных", "show_unread_badge", true)
        SwitchRow(vm, me, "Индикатор «в сети»", "show_online_dot", true)
        SwitchRow(vm, me, "Подтверждать удаление", "confirm_before_delete", true)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Фон чата")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).clickable { vm.updateMe("custom_wallpaper_color" to "") }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_close, size = 18.dp) }
                ACCENTS.forEach { c -> Box(Modifier.size(38.dp).background(colorOf(c), CircleShape).clickable { vm.updateMe("custom_wallpaper_color" to c) }) }
            }
        }

        SectionTitle("Уведомления")
        SwitchRow(vm, me, "Push-уведомления", "push_notifications", true)
        SwitchRow(vm, me, "Показывать текст сообщения", "notification_preview", true)
        SwitchRow(vm, me, "Не беспокоить", "dnd_mode", false)
        SwitchRow(vm, me, "Упоминания в группах", "group_mention_notifications", true)

        SectionTitle("Конфиденциальность")
        SwitchRow(vm, me, "Отчёты о прочтении", "read_receipts", true)
        SwitchRow(vm, me, "Автоматически помечать прочитанным", "auto_mark_read", true)
        SwitchRow(vm, me, "Показывать статус «в сети»", "show_activity_status", true, "Если выключить, вы тоже не увидите статусы других")
        SwitchRow(vm, me, "Индикатор набора текста", "typing_indicators", true)
        ChoiceRow(vm, me, "Кто видит профиль", "profile_visibility", listOf("everyone" to "Все", "contacts" to "Контакты", "nobody" to "Никто"), "everyone")

        SectionTitle("Звонки")
        SwitchRow(vm, me, "Уведомления о звонках", "call_notifications", true)
        SwitchRow(vm, me, "Мелодия звонка", "call_ringtone", true)
        SwitchRow(vm, me, "Вибрация при звонке", "vibrate_on_call", true)
        SwitchRow(vm, me, "Автоответ на звонки", "auto_answer_calls", false)
        ChoiceRow(vm, me, "Качество видео", "video_call_quality", listOf("auto" to "Авто", "sd" to "SD", "hd" to "HD"), "auto")
        SwitchRow(vm, me, "Зеркалить свою камеру", "video_call_mirror", true)

        SectionTitle("Медиа и музыка")
        SwitchRow(vm, me, "Автозагрузка медиа", "auto_download_media", true)
        SwitchRow(vm, me, "Автовоспроизведение голосовых", "auto_play_voice", false)
        SwitchRow(vm, me, "Следующий трек автоматически", "music_auto_next", true)
        Column(Modifier.padding(horizontal = 16.dp)) {
            var vol by remember(me.i("music_volume", 80)) { mutableFloatStateOf(me.i("music_volume", 80).toFloat()) }
            Text("Громкость музыки: ${vol.toInt()}%")
            Slider(value = vol, onValueChange = { vol = it }, valueRange = 0f..100f, onValueChangeFinished = { vm.updateMe("music_volume" to vol.toInt()) })
        }

        SectionTitle("Подписка")
        ListItem(
            headlineContent = { Text("fPro / fPremium") }, supportingContent = { Text("Текущий план: ${Plans.label(Plans.tier(me))}") },
            leadingContent = { Ico(R.drawable.ic_crown) }, modifier = Modifier.clickable { showPro = true },
        )

        SectionTitle("Сервер данных")
        ListItem(
            headlineContent = { Text("Репозиторий") }, supportingContent = { Text(Prefs.ghRepoFull.ifBlank { "не задан" }) },
        )

        SectionTitle("Аккаунт")
        ListItem(
            headlineContent = { Text("Выйти") }, leadingContent = { Ico(R.drawable.ic_logout, tint = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { vm.logout() },
        )
        Text("fMessage 1.0 · Material 3 Expressive", Modifier.padding(20.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
    if (showPro) FProSheet(vm, onDismiss = { showPro = false })
}
