package com.virtualandroid.app

import android.content.Context
import com.virtualandroid.app.core.RomEntry
import org.json.JSONObject
import java.io.File

/** Sürüm listesi app/src/main/assets/versions.json dosyasından okunur. */
object Catalog {
    fun load(ctx: Context): List<RomEntry> {
        val text = ctx.assets.open("versions.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("versions")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RomEntry(
                id = o.getString("id"),
                label = o.getString("label"),
                source = o.optString("source", "mediafire"),
                url = o.optString("url", "").trim(),
                sha256 = o.optString("sha256", "").trim().lowercase()
            )
        }
    }

    fun find(ctx: Context, id: String): RomEntry? = load(ctx).firstOrNull { it.id == id }
}

object Storage {
    /** İndirilen Android dosyalarının saklandığı klasör (uygulamaya özel, dahili depolama). */
    fun root(ctx: Context): File = File(ctx.filesDir, "roms").also { it.mkdirs() }
}
