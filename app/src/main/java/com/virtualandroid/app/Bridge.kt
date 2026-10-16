package com.virtualandroid.app

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import com.virtualandroid.app.core.JobState
import com.virtualandroid.app.core.Phase
import com.virtualandroid.app.core.RomInstaller
import com.virtualandroid.app.engine.EngineResult
import com.virtualandroid.app.engine.Engines
import org.json.JSONArray
import org.json.JSONObject

/**
 * Arayüzün (index.html) çağırdığı fonksiyonlar. JS tarafında window.Android olarak görünür.
 * Hepsi String döndürür (JSON veya düz metin).
 */
class Bridge(private val ctx: Context) {

    private fun installer() = RomInstaller(Storage.root(ctx))

    /** [{"id":"android7","label":"Android 7","available":true}, ...] */
    @JavascriptInterface
    fun getCatalog(): String = try {
        val arr = JSONArray()
        Catalog.load(ctx).forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("available", it.available))
        }
        arr.toString()
    } catch (e: Exception) {
        "[]"
    }

    /** {"android7":{"phase":"downloading","done":123,"total":456,"message":""}, ...} */
    @JavascriptInterface
    fun getStatus(): String {
        val root = JSONObject()
        val inst = installer()
        val list = try { Catalog.load(ctx) } catch (e: Exception) { emptyList() }
        for (e in list) {
            val job = Jobs.state(e.id)
            val s: JobState = when {
                job != null && Jobs.active(e.id) -> job
                inst.isReady(e.id) -> JobState(Phase.READY)
                job != null && job.phase == Phase.ERROR -> job
                else -> JobState(Phase.NONE, done = inst.partialBytes(e.id), message = job?.message ?: "")
            }
            root.put(
                e.id,
                JSONObject()
                    .put("phase", s.phase.name.lowercase())
                    .put("done", s.done)
                    .put("total", s.total)
                    .put("message", s.message)
            )
        }
        return root.toString()
    }

    /** "ok" veya hata metni döner. */
    @JavascriptInterface
    fun startDownload(id: String): String {
        val entry = try { Catalog.find(ctx, id) } catch (e: Exception) { null } ?: return "Sürüm bulunamadı"
        if (!entry.available) return "Bu sürüm için indirme bağlantısı henüz eklenmedi"
        if (installer().isReady(id) || Jobs.active(id)) return "ok"
        return try {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(DownloadService.EXTRA_ID, id))
            "ok"
        } catch (e: Exception) {
            "İndirme başlatılamadı: ${e.message}"
        }
    }

    @JavascriptInterface
    fun cancelDownload(id: String) {
        Jobs.cancelFlag(id).set(true)
    }

    /** {"ok":false,"message":"..."} */
    @JavascriptInterface
    fun startVm(romId: String, vmName: String): String {
        val inst = installer()
        val r = if (!inst.isReady(romId)) {
            EngineResult(false, "Önce Android dosyasının inmesi gerekiyor.")
        } else {
            Engines.current.start(vmName, inst.romDir(romId))
        }
        return JSONObject().put("ok", r.ok).put("message", r.message).toString()
    }
}
