@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fworld.fmessage.AppVM
import com.fworld.fmessage.R
import com.fworld.fmessage.data.Prefs

@Composable
fun AuthScreen(vm: AppVM) {
    val ready by vm.ghReady.collectAsState()
    var mode by remember { mutableStateOf("login") } // login | register | server
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(Prefs.ghRepoFull) }
    var ghToken by remember { mutableStateOf(Prefs.ghToken) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }

    val shown = if (!ready) "server" else mode
    fun go(m: String) { mode = m; error = "" }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background)))
            .systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surfaceContainerHigh, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_f_message_logo), null, Modifier.size(52.dp), tint = Color.Unspecified) }
            }
            Spacer(Modifier.height(16.dp))
            Text("fMessage", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                when (shown) { "register" -> "Создайте аккаунт"; "server" -> "Подключение к серверу данных"; else -> "Войдите в мессенджер" },
                style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
                    label = "auth",
                ) { m ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (error.isNotBlank()) {
                            Surface(color = scheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                                Text(error, Modifier.padding(12.dp), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (m == "server") {
                            Text(
                                "Все данные хранятся в приватном GitHub-репозитории. Укажите его и токен доступа (fine-grained, право Contents: Read and write).",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("Репозиторий (owner/repo)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = ghToken, onValueChange = { ghToken = it }, label = { Text("GitHub токен") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = !loading && repo.contains("/") && ghToken.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp),
                                onClick = {
                                    loading = true; error = ""
                                    vm.saveGithubConfig(repo, ghToken) { err -> loading = false; if (err != null) error = err }
                                },
                            ) { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary) else Text("Подключиться") }
                        } else {
                            OutlinedTextField(
                                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                                leadingIcon = { Ico(R.drawable.ic_mail) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true,
                                leadingIcon = { Ico(R.drawable.ic_lock) },
                                trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Ico(R.drawable.ic_eye) } },
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                            )
                            if (m == "register") {
                                OutlinedTextField(
                                    value = confirm, onValueChange = { confirm = it }, label = { Text("Повторите пароль") }, singleLine = true,
                                    leadingIcon = { Ico(R.drawable.ic_lock) }, visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Button(
                                enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                onClick = {
                                    error = ""
                                    if (m == "register") {
                                        if (password.length < 6) { error = "Пароль должен быть не короче 6 символов"; return@Button }
                                        if (password != confirm) { error = "Пароли не совпадают"; return@Button }
                                    }
                                    loading = true
                                    val done: (String?) -> Unit = { err -> loading = false; if (err != null) error = err }
                                    if (m == "login") vm.login(email, password, done) else vm.register(email, password, done)
                                },
                            ) {
                                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                                else Text(if (m == "login") "Войти" else "Зарегистрироваться")
                            }
                            TextButton(onClick = { go(if (m == "login") "register" else "login") }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (m == "login") "Нет аккаунта? Регистрация" else "Уже есть аккаунт? Войти")
                            }
                        }
                    }
                }
            }
        }
    }
}
