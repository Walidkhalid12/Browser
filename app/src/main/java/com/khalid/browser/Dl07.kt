package com.khalid.browser

import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URLDecoder
import java.util.Locale

const val APK = "application/vnd.android.package-archive"

/** 0.7.0: picks a good file name and type for a download. */
object DlName {
    private val generic = setOf("", "application/octet-stream", "binary/octet-stream", "application/x-download",
        "application/download", "application/force-download", "application/unknown", "application/x-msdownload")

    private fun extType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext == "apk") return APK
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
    }

    /** The real type: the server's, unless it is a "just bytes" type and the name says more. */
    fun typeOf(name: String, mime: String): String {
        val m = mime.substringBefore(';').trim().lowercase(Locale.US)
        val byName = extType(name)
        return when {
            name.endsWith(".apk", true) -> APK
            m in generic -> byName.ifBlank { m }
            else -> m
        }
    }

    /** The name from Content-Disposition (filename* first), then the address, cleaned up for the phone. */
    fun of(url: String, contentDisposition: String?, mime: String): String {
        val cd = contentDisposition.orEmpty()
        var name = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), m.groupValues[1].ifBlank { "UTF-8" }) }.getOrNull()
        } ?: Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
          ?: Regex("filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        if (name.isNullOrBlank()) {
            val last = runCatching { URLDecoder.decode(android.net.Uri.parse(url).lastPathSegment.orEmpty(), "UTF-8") }.getOrDefault("")
            name = if (last.contains('.') && last.length <= 120) last
            else URLUtil.guessFileName(url, contentDisposition, mime.ifBlank { null })
        }
        var n = name!!.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().trim('.')
        if (n.isBlank()) n = "download"
        // guessFileName turns unknown types into ".bin"; keep the address's real extension when it has one
        if (n.endsWith(".bin", true)) {
            val urlExt = MimeTypeMap.getFileExtensionFromUrl(url).orEmpty()
            if (urlExt.isNotBlank() && !urlExt.equals("bin", true)) n = n.dropLast(4) + "." + urlExt
        }
        // no extension: add one from the type
        if (!n.contains('.')) {
            val m = mime.substringBefore(';').trim().lowercase(Locale.US)
            val ext = if (m == APK) "apk" else MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
            if (!ext.isNullOrBlank()) n = "$n.$ext"
        }
        if (n.length > 120) { val e = n.substringAfterLast('.', ""); n = n.take(110) + if (e.isNotBlank() && e.length < 8) ".$e" else "" }
        return n
    }

    /** Why a download stopped, in plain words (DownloadManager reason codes). */
    fun failReason(r: Int): String = when (r) {
        1006 -> "phone storage is full"
        1007 -> "storage not available"
        1009 -> "file already exists"
        1001 -> "can't save the file"
        1002, 1004 -> "connection problem"
        1005 -> "too many redirects"
        1008 -> "can't resume"
        1010 -> "the file arrived incomplete"
        1012 -> "the file keeps changing on the site"
        401, 403 -> "the site refused it"
        404 -> "file not found"
        in 500..599 -> "the site has a problem"
        else -> ""
    }

    fun pauseReason(r: Int): String = when (r) {
        1 -> "retrying soon"
        2 -> "waiting for network"
        3 -> "waiting for Wi-Fi"
        11 -> "stopped, tap play to continue"
        else -> "paused"
    }

    /** 1.0.2: "40s left", "3 min left", "2 h left". */
    fun timeLeft(s: Long): String = when { s < 60 -> "${s}s left"; s < 3600 -> "${s / 60} min left"; else -> "${s / 3600} h left" }
}
