package com.fworld.fmessage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.fworld.fmessage.ui.RootScreen

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        setContent { RootScreen(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val chatId = i?.getStringExtra("chat_id") ?: return
        i.removeExtra("chat_id")
        vm.pendingOpenChat = chatId
        vm.tryOpenPending()
    }

    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { super.onStop(); vm.onBackground() }
}
