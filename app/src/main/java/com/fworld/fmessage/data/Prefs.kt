package com.fworld.fmessage.data

import android.content.Context
import android.content.SharedPreferences
import com.fworld.fmessage.BuildConfig

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("fmessage", Context.MODE_PRIVATE)
    }

    /** Current user id (session). */
    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply() }

    var themeMode: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** "owner/repo" of the private data repository. */
    var ghRepoFull: String
        get() = sp.getString("gh_repo", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DATA_REPO
        set(v) { sp.edit().putString("gh_repo", v.trim()).apply() }

    var ghToken: String
        get() = sp.getString("gh_token", null)?.takeIf { it.isNotBlank() } ?: ""
        set(v) { sp.edit().putString("gh_token", v.trim()).apply() }
}
