package com.virtualandroid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.virtualandroid.app.core.CancelledException
import com.virtualandroid.app.core.Errors
import com.virtualandroid.app.core.JobState
import com.virtualandroid.app.core.Phase
import com.virtualandroid.app.core.RomEntry
import com.virtualandroid.app.core.RomInstaller
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * İndirme + kurulum işini ön plan servisi olarak çalıştırır; böylece ekran kapansa
 * veya uygulamadan çıkılsa bile büyük dosya inmeye devam eder.
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_ID = "rom_id"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1001
    }

    private val pool = Executors.newSingleThreadExecutor()
    private val running = AtomicInteger(0)

    @Volatile
    private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() çağrısından sonra hemen startForeground() çağrılmak zorunda
        startForegroundCompat(progressNotification("Android indiriliyor", "Hazırlanıyor…", 0, true))

        val id = intent?.getStringExtra(EXTRA_ID)
        val entry = id?.let { try { Catalog.find(this, it) } catch (e: Exception) { null } }
        if (id == null || entry == null || !entry.available) {
            if (id != null) Jobs.set(id, JobState(Phase.ERROR, message = "Sürüm bulunamadı veya bağlantısı eklenmemiş."))
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (Jobs.active(id)) return START_NOT_STICKY

        val queued = running.get() > 0
        Jobs.cancelFlag(id).set(false)
        Jobs.set(id, JobState(Phase.RESOLVING, message = if (queued) "Sırada bekliyor" else ""))
        running.incrementAndGet()
        pool.execute { runJob(entry) }
        return START_NOT_STICKY
    }

    private fun runJob(entry: RomEntry) {
        val installer = RomInstaller(Storage.root(this))
        try {
            installer.install(entry, { s -> onState(entry, s) }, { Jobs.cancelFlag(entry.id).get() })
            Jobs.set(entry.id, JobState(Phase.READY))
            notifyDone(entry, true, "Artık başlatabilirsin.")
        } catch (e: CancelledException) {
            Jobs.set(entry.id, JobState(Phase.NONE, message = "Durduruldu"))
        } catch (e: Throwable) {
            val msg = Errors.friendly(e)
            Jobs.set(entry.id, JobState(Phase.ERROR, message = msg))
            notifyDone(entry, false, msg)
        } finally {
            if (running.decrementAndGet() == 0) stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (running.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onState(entry: RomEntry, s: JobState) {
        Jobs.set(entry.id, s)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val pct = if (s.total > 0) ((s.done * 100) / s.total).toInt().coerceIn(0, 100) else 0
        val what = when (s.phase) {
            Phase.DOWNLOADING -> "indiriliyor"
            Phase.VERIFYING -> "doğrulanıyor"
            Phase.EXTRACTING -> "kuruluyor"
            else -> "hazırlanıyor"
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            progressNotification("${entry.label} $what", if (s.total > 0) "%$pct" else "", pct, s.total <= 0)
        )
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotification(title: String, text: String, pct: Int, indeterminate: Boolean): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyDone(entry: RomEntry, ok: Boolean, text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ok) "${entry.label} hazır" else "${entry.label} indirilemedi")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(entry.id.hashCode(), n)
    }
}
