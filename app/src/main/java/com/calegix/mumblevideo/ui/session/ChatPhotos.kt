package com.calegix.mumblevideo.ui.session

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Where photos taken for chat land: one file per shot in the cache, old shots cleared out. */
object ChatPhotos {
    private const val DIR = "chat-photos"

    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // Each photo has been sent (or abandoned) by the time the next is taken.
        dir.listFiles()?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".jpg", dir)
        return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    }
}
