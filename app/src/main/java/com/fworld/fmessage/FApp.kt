package com.fworld.fmessage

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.fworld.fmessage.data.Api
import com.fworld.fmessage.data.Prefs

class FApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifier.init(this)
    }

    // Coil goes through the same OkHttp client, which adds GitHub auth for private-repo media.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(Api.http).crossfade(true).build()
}
