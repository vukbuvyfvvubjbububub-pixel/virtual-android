package com.virtualandroid.app.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun open(url: String, rangeFrom: Long = 0L, accept: String = "*/*"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        // Sıkıştırma kapalı: Content-Length ile gerçek dosya boyutu aynı kalsın
        c.setRequestProperty("Accept-Encoding", "identity")
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        return c
    }
}

object Downloader {

    private fun mb(bytes: Long): Long = bytes shr 20

    /**
     * url'yi part dosyasına indirir. part zaten varsa kaldığı yerden devam eder (sunucu Range destekliyorsa).
     * Durdurulursa CancelledException fırlatır ve part dosyasını bırakır.
     */
    fun download(
        url: String,
        part: File,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        part.absoluteFile.parentFile?.mkdirs()
        var resumeFrom = if (part.isFile) part.length() else 0L
        var restarted = false
        while (true) {
            val conn = Http.open(url, resumeFrom)
            try {
                val code = conn.responseCode
                if (code == 416 && resumeFrom > 0 && !restarted) {
                    // Sunucu "bu aralık yok" dedi: yarım dosya geçersiz, baştan başla
                    part.delete()
                    resumeFrom = 0L
                    restarted = true
                    continue
                }
                if (code != 200 && code != 206) {
                    throw InstallException("İndirme sunucusu hata verdi (HTTP $code).")
                }
                val type = conn.contentType.orEmpty()
                if (type.startsWith("text/html", ignoreCase = true)) {
                    throw InstallException(
                        "Sunucu dosya yerine bir web sayfası döndürdü. MediaFire günlük limiti veya engeli olabilir, biraz sonra tekrar dene."
                    )
                }
                val append = code == 206
                val startAt = if (append) resumeFrom else 0L
                val total = totalLength(conn, code, startAt)

                val dir = part.absoluteFile.parentFile
                if (total > 0 && dir != null) {
                    val need = total - startAt
                    if (dir.usableSpace < need + (64L shl 20)) {
                        throw InstallException("Yetersiz depolama alanı. İndirmek için en az ${mb(need)} MB boş yer gerekiyor.")
                    }
                }
                copy(conn, part, append, startAt, total, onProgress, cancelled)
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun totalLength(conn: HttpURLConnection, code: Int, startAt: Long): Long {
        if (code == 206) {
            conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?.let { return it }
            val len = conn.contentLengthLong
            return if (len >= 0) startAt + len else -1L
        }
        return conn.contentLengthLong
    }

    private fun copy(
        conn: HttpURLConnection,
        part: File,
        append: Boolean,
        startAt: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val buf = ByteArray(64 * 1024)
        var done = startAt
        var lastReport = 0L
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastReport > 250_000_000L) {
                        lastReport = now
                        onProgress(done, total)
                    }
                }
            }
        }
        onProgress(done, total)
        if (total > 0 && done != total) {
            throw InstallException(
                "İndirme yarıda kesildi (${mb(done)} / ${mb(total)} MB). Tekrar denersen kaldığı yerden devam eder."
            )
        }
    }

    fun sha256(file: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        var lastReport = 0L
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (cancelled()) throw CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > 250_000_000L) {
                    lastReport = now
                    onProgress(done, total)
                }
            }
        }
        onProgress(total, total)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
