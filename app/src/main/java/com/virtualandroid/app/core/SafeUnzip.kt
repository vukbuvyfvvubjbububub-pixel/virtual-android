package com.virtualandroid.app.core

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * ZIP'i güvenle açar:
 *  - klasör dışına yazmaya çalışan girdileri (../ ile "zip slip") reddeder
 *  - toplam boyut ve dosya sayısı sınırı var (zip bombası koruması)
 *  - açmadan önce boş alan kontrolü yapar
 */
object SafeUnzip {
    data class Result(val files: Int, val bytes: Long)

    fun extract(
        zip: File,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        maxTotalBytes: Long = 48L shl 30,
        maxEntries: Int = 200_000
    ): Result {
        val root = dest.canonicalFile
        root.mkdirs()
        try {
            return ZipFile(zip).use { z ->
                val entries = z.entries().toList()
                if (entries.size > maxEntries) throw ArchiveException("ZIP çok fazla dosya içeriyor.")

                var declared = 0L
                for (e in entries) if (!e.isDirectory && e.size > 0) declared += e.size
                if (declared > maxTotalBytes) throw ArchiveException("ZIP içeriği beklenenden çok büyük.")
                if (root.usableSpace < declared + (128L shl 20)) {
                    throw InstallException("Yetersiz depolama alanı. Açmak için en az ${declared shr 20} MB boş yer gerekiyor.")
                }

                var written = 0L
                var files = 0
                var lastReport = 0L
                val buf = ByteArray(64 * 1024)

                for (e in entries) {
                    if (cancelled()) throw CancelledException()
                    val name = e.name
                    if (name.indexOf('\u0000') >= 0) throw ArchiveException("ZIP içinde geçersiz dosya adı var.")

                    val out = File(root, name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw ArchiveException("ZIP güvenli değil, klasör dışına yazmaya çalışan dosya var: $name")
                    }
                    if (e.isDirectory) {
                        if (!out.isDirectory && !out.mkdirs()) throw InstallException("Klasör oluşturulamadı: $name")
                        continue
                    }
                    val parent = out.parentFile
                    if (parent == null || !(parent.isDirectory || parent.mkdirs())) {
                        throw InstallException("Klasör oluşturulamadı: $name")
                    }
                    z.getInputStream(e).use { input ->
                        FileOutputStream(out).use { os ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                written += n
                                if (written > maxTotalBytes) throw ArchiveException("ZIP içeriği beklenenden çok büyük.")
                                val now = System.nanoTime()
                                if (now - lastReport > 250_000_000L) {
                                    lastReport = now
                                    onProgress(written, declared)
                                }
                            }
                        }
                    }
                    files++
                }
                onProgress(written, declared)
                Result(files, written)
            }
        } catch (e: ZipException) {
            throw ArchiveException("İndirilen ZIP bozuk veya geçerli bir ZIP değil (${e.message}).")
        }
    }
}
