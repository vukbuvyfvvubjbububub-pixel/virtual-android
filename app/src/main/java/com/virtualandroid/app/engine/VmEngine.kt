package com.virtualandroid.app.engine

import java.io.File

data class EngineResult(val ok: Boolean, val message: String)

/**
 * Sanal makineyi gerçekten çalıştıracak motorun arayüzü.
 *
 * Arayüzdeki "Başlat" düğmesi buraya gelir. Motor henüz bağlı değil: şimdilik NoEngine kullanılıyor.
 * Motor eklendiğinde Engines.current değiştirilir, başka hiçbir yere dokunmak gerekmez.
 *
 * Not: start() WebView'in arka plan thread'inden çağrılır. Ekran açacaksan Activity'ye geçişi
 * ana thread'e (Handler(Looper.getMainLooper())) taşı.
 */
interface VmEngine {
    /** romDir: indirilmiş ve açılmış Android dosyalarının klasörü. */
    fun start(vmName: String, romDir: File): EngineResult
}

object NoEngine : VmEngine {
    override fun start(vmName: String, romDir: File) = EngineResult(
        false,
        "Android motoru henüz bağlı değil. İndirme tamam, çalıştırma bir sonraki adımda eklenecek."
    )
}

object Engines {
    @Volatile
    var current: VmEngine = NoEngine
}
