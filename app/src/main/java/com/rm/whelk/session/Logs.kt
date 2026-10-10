package com.rm.whelk.session

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Session logs: one file per session, in a folder the user picked or a file they named. */
object Logs {
    fun fileName(s: Session): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date())
        val name = s.title.replace(Regex("[^A-Za-z0-9@._-]+"), "-").trim('-').ifEmpty { "session" }
        return "$name-$stamp.txt"
    }

    fun startInFolder(context: Context, s: Session, folder: String) {
        runCatching {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: return
            val name = fileName(s)
            val f = dir.createFile("text/plain", name) ?: return
            s.watch.startLog(context, f.uri, name)
        }
    }
}
