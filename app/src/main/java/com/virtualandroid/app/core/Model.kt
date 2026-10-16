package com.virtualandroid.app.core

/** versions.json içindeki bir Android sürümü. */
data class RomEntry(
    val id: String,
    val label: String,
    /** "mediafire" (sayfa bağlantısı, indirme linki sayfadan çıkarılır) veya "direct" (doğrudan dosya linki) */
    val source: String,
    val url: String,
    /** Boş bırakılabilir. Doluysa indirilen dosyanın SHA-256 değeri ile karşılaştırılır. */
    val sha256: String
) {
    val available: Boolean get() = url.isNotBlank()
}

enum class Phase { NONE, RESOLVING, DOWNLOADING, VERIFYING, EXTRACTING, READY, ERROR }

data class JobState(
    val phase: Phase,
    val done: Long = 0L,
    val total: Long = 0L,
    val message: String = ""
)

open class InstallException(message: String) : Exception(message)

/** ZIP bozuk, güvensiz veya fazla büyük. İndirilen zip dosyası silinir. */
class ArchiveException(message: String) : InstallException(message)

/** Kullanıcı durdurdu. Yarım inen dosya kalır, sonra kaldığı yerden devam eder. */
class CancelledException : Exception("Durduruldu")

object Errors {
    fun friendly(e: Throwable): String = when (e) {
        is InstallException -> e.message ?: "Bilinmeyen hata."
        is java.net.UnknownHostException -> "İnternet bağlantısı yok veya sunucuya ulaşılamıyor."
        is java.net.SocketTimeoutException -> "Bağlantı zaman aşımına uğradı. Tekrar dene, kaldığı yerden devam eder."
        is java.io.IOException -> "Dosya veya ağ hatası (${e.message ?: e.javaClass.simpleName}). Tekrar dene, kaldığı yerden devam eder."
        else -> "Beklenmeyen hata: ${e.javaClass.simpleName}"
    }
}
