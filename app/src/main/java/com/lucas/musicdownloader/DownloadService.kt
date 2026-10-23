package com.lucas.musicdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Fait les téléchargements en arrière-plan : ils continuent quand on quitte l'app.
// Les demandes s'enchaînent dans une file d'attente.
class DownloadService : Service() {

    private data class DownloadJob(val url: String, val playlist: Boolean, val title: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<DownloadJob>()
    private var worker: Job? = null
    private var currentProcessId: String? = null
    private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Téléchargements en cours", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            if (worker?.isActive != true) stopSelfNow()
            return START_NOT_STICKY
        }

        // Android exige d'afficher la notification tout de suite
        startInForeground(progressNotification("Préparation…", -1))

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (worker?.isActive != true) stopSelfNow()
            return START_NOT_STICKY
        }
        queue.addLast(
            DownloadJob(
                url = url,
                playlist = intent.getBooleanExtra(EXTRA_PLAYLIST, false),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { url },
            )
        )
        publish(DownloadState.status.value.copy(queued = queue.size))

        if (worker?.isActive != true) {
            worker = scope.launch { processQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun processQueue() {
        // Empêche le téléphone de mettre le processeur en veille, écran éteint
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicDownloader:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        while (queue.isNotEmpty()) {
            val job = queue.removeFirst()
            cancelled = false
            val result = runJob(job)
            publish(DownloadStatus(active = queue.isNotEmpty(), title = job.title, message = result, queued = queue.size))
            showDoneNotification(job.title, result)
        }
        stopSelfNow()
    }

    // Télécharge un élément et renvoie le message à afficher
    private suspend fun runJob(job: DownloadJob): String {
        try {
            publish(DownloadStatus(active = true, title = job.title, message = "Préparation…", queued = queue.size))
            Ytdl.ensureInitialized(this) { status ->
                if (status.isNotEmpty()) publish(DownloadState.status.value.copy(message = status))
            }

            val tmpDir = File(cacheDir, "downloads/${System.currentTimeMillis()}").apply { mkdirs() }
            val processId = "download-${System.currentTimeMillis()}"
            currentProcessId = processId

            val error = withContext(Dispatchers.IO) {
                val output = if (job.playlist) {
                    "${tmpDir.absolutePath}/%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
                } else {
                    "${tmpDir.absolutePath}/%(title)s.%(ext)s"
                }
                val request = YoutubeDLRequest(job.url).apply {
                    addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
                    addOption("-x") // audio uniquement
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--add-metadata")
                    addOption("--windows-filenames") // évite les caractères refusés par Android
                    if (job.playlist) addOption("--ignore-errors") // une vidéo indisponible ne bloque pas le reste
                    addOption("-o", output)
                }
                var item = ""
                try {
                    YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                        Regex("Downloading item (\\d+) of (\\d+)").find(line)?.let {
                            item = "Titre ${it.groupValues[1]}/${it.groupValues[2]} · "
                        }
                        val percent = progress.toInt()
                        onProgress(job, percent, "${item}Téléchargement… ${percent.coerceAtLeast(0)} %")
                    }
                    null
                } catch (e: Exception) {
                    // Pour une playlist, certaines vidéos ont pu réussir : on enregistre quand même
                    e
                }
            }
            currentProcessId = null

            if (cancelled) {
                tmpDir.deleteRecursively()
                return "Téléchargement annulé."
            }

            publish(DownloadStatus(active = true, title = job.title, message = "Enregistrement dans Musique…", queued = queue.size))
            val saved = withContext(Dispatchers.IO) {
                tmpDir.walkTopDown()
                    .filter { it.isFile && it.extension == "mp3" }
                    .sortedBy { it.path }
                    .map { saveToMusic(it, tmpDir) }
                    .toList()
            }
            tmpDir.deleteRecursively()

            return when {
                saved.isEmpty() && error != null -> "❌ Échec du téléchargement.\n${Ytdl.shortError(error)}"
                saved.isEmpty() -> "Aucun fichier MP3 obtenu."
                job.playlist -> {
                    val failed = if (error != null) "\n⚠️ Certains titres n'ont pas pu être téléchargés." else ""
                    "✅ ${saved.size} titres enregistrés dans Musique/MusicDownloader$failed"
                }
                else -> "✅ Enregistré dans Musique/MusicDownloader :\n${saved.joinToString("\n")}"
            }
        } catch (e: Exception) {
            return "❌ Erreur : ${Ytdl.shortError(e)}"
        }
    }

    // Appelé très souvent par yt-dlp : on limite les mises à jour de la notification
    private fun onProgress(job: DownloadJob, percent: Int, message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 500) return
        lastNotify = now
        scope.launch {
            publish(DownloadStatus(active = true, title = job.title, progress = percent, message = message, queued = queue.size))
        }
    }

    private fun publish(status: DownloadStatus) {
        DownloadState.status.value = status
        if (status.active) {
            val text = if (status.queued > 0) "${status.message}\n+${status.queued} en attente" else status.message
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_PROGRESS, progressNotification(text, status.progress, status.title))
        }
    }

    private fun cancelAll() {
        queue.clear()
        cancelled = true
        currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
    }

    // Copie le MP3 dans le dossier public Musique pour qu'il apparaisse dans les lecteurs audio.
    // Les playlists vont dans un sous-dossier à leur nom.
    private fun saveToMusic(file: File, root: File): String {
        val subFolder = file.parentFile?.relativeTo(root)?.path.orEmpty()
        val relativePath = listOf(Environment.DIRECTORY_MUSIC, "MusicDownloader", subFolder)
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier dans Musique")
        contentResolver.openOutputStream(uri).use { out ->
            file.inputStream().use { input -> input.copyTo(out!!) }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return file.nameWithoutExtension
    }

    // ---------- Notifications ----------

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(text: String, progress: Int, title: String = "Téléchargement") =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "Annuler",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun showDoneNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(message.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_DONE_BASE + (System.currentTimeMillis() % 100000).toInt(), notification)
    }

    private fun startInForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(this, NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun stopSelfNow() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE_BASE = 1000
        private const val ACTION_CANCEL = "com.lucas.musicdownloader.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, url: String, playlist: Boolean, title: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
