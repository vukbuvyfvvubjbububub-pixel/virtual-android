package com.virtualandroid.app.core

import java.io.File

/**
 * Bir Android sürümünü indirir, doğrular, açar ve "hazır" olarak işaretler.
 *
 * Klasör düzeni (rootDir altında):
 *   <id>.zip.part  yarım inen dosya (kaldığı yerden devam eder)
 *   <id>.zip       inen zip (açıldıktan sonra silinir)
 *   <id>.tmp       açılma sırasındaki geçici klasör
 *   <id>/          hazır klasör, içinde .ready işareti vardır
 */
class RomInstaller(private val rootDir: File, private val keepZip: Boolean = false) {

    companion object {
        const val READY = ".ready"
    }

    fun romDir(id: String): File = File(rootDir, id)

    fun isReady(id: String): Boolean = File(romDir(id), READY).isFile

    /** Yarım inen dosyanın boyutu (yoksa 0). */
    fun partialBytes(id: String): Long {
        val part = File(rootDir, "$id.zip.part")
        return if (part.isFile) part.length() else 0L
    }

    fun install(entry: RomEntry, onState: (JobState) -> Unit, cancelled: () -> Boolean) {
        if (!entry.available) throw InstallException("Bu sürüm için indirme bağlantısı henüz eklenmedi.")
        if (isReady(entry.id)) {
            onState(JobState(Phase.READY))
            return
        }
        rootDir.mkdirs()
        val zip = File(rootDir, "${entry.id}.zip")
        val part = File(rootDir, "${entry.id}.zip.part")

        if (!zip.isFile) {
            onState(JobState(Phase.RESOLVING))
            val direct = if (entry.source == "mediafire") Mediafire.resolve(entry.url) else entry.url
            if (cancelled()) throw CancelledException()

            onState(JobState(Phase.DOWNLOADING, partialBytes(entry.id), 0L))
            Downloader.download(direct, part, { d, t -> onState(JobState(Phase.DOWNLOADING, d, t)) }, cancelled)
            if (!part.renameTo(zip)) throw InstallException("İndirilen dosya yeniden adlandırılamadı.")
        }

        if (entry.sha256.isNotBlank()) {
            onState(JobState(Phase.VERIFYING))
            val actual = Downloader.sha256(zip, cancelled) { d, t -> onState(JobState(Phase.VERIFYING, d, t)) }
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                zip.delete()
                throw InstallException("Dosya doğrulaması başarısız (SHA-256 uyuşmuyor). Dosya silindi, tekrar indirmen gerekiyor.")
            }
        }

        val tmp = File(rootDir, "${entry.id}.tmp")
        tmp.deleteRecursively()
        onState(JobState(Phase.EXTRACTING))
        try {
            SafeUnzip.extract(zip, tmp, { d, t -> onState(JobState(Phase.EXTRACTING, d, t)) }, cancelled)
            File(tmp, READY).writeText("ok")
        } catch (e: ArchiveException) {
            tmp.deleteRecursively()
            zip.delete()
            throw e
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw e
        }

        romDir(entry.id).deleteRecursively()
        if (!tmp.renameTo(romDir(entry.id))) {
            tmp.deleteRecursively()
            throw InstallException("Açılan dosyalar yerine taşınamadı.")
        }
        if (!keepZip) zip.delete()
        onState(JobState(Phase.READY))
    }
}
