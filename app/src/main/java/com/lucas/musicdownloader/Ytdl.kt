package com.lucas.musicdownloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Prépare yt-dlp et ffmpeg une seule fois, partagé entre l'écran et le service
object Ytdl {
    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    suspend fun ensureInitialized(context: Context, onStatus: (String) -> Unit = {}) = mutex.withLock {
        if (initialized) return@withLock
        val appContext = context.applicationContext
        onStatus("Préparation (première fois, ça peut prendre un moment)…")
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
        }
        // YouTube change souvent : on met yt-dlp à jour à chaque lancement
        onStatus("Mise à jour de yt-dlp…")
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            }
        } catch (e: Exception) {
            // Pas bloquant : on tente quand même avec la version embarquée
        }
        initialized = true
        onStatus("")
    }

    // Les erreurs de yt-dlp sont longues : on garde la ligne utile
    fun shortError(e: Exception): String {
        val message = e.message ?: return e.javaClass.simpleName
        return message.lines().lastOrNull { it.contains("ERROR") }?.trim() ?: message.lines().first().take(300)
    }
}

// État du téléchargement, publié par le service et affiché par l'écran
data class DownloadStatus(
    val active: Boolean = false,
    val title: String = "",
    val progress: Int = -1, // -1 = en cours sans pourcentage connu
    val message: String = "",
    val queued: Int = 0,
)

object DownloadState {
    val status = MutableStateFlow(DownloadStatus())
}
