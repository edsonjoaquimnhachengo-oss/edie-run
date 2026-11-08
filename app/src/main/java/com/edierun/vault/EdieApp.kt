package com.edierun.vault

import android.app.Application
import com.edierun.vault.download.DownloadManager

class EdieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
    }
}
