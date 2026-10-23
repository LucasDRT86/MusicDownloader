package com.lucas.musicdownloader

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var initialized = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        downloadButton.setOnClickListener { startDownload(urlInput.text.toString()) }

        handleShareIntent(intent)
    }

    // Appelé quand l'app est déjà ouverte et qu'on partage une nouvelle vidéo
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // YouTube partage parfois "Titre https://youtu.be/xxx" : on garde juste le lien
        val url = Regex("https?://\\S+").find(sharedText)?.value ?: sharedText
        urlInput.setText(url)
        startDownload(url)
    }

    private fun startDownload(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Colle d'abord une URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Un téléchargement est déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        downloadButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        lifecycleScope.launch {
            try {
                ensureInitialized()

                setStatus("Téléchargement…")
                val tmpDir = File(cacheDir, "downloads").apply {
                    deleteRecursively()
                    mkdirs()
                }
                withContext(Dispatchers.IO) {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("-x") // audio uniquement
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                        addOption("--add-metadata")
                        addOption("-o", "${tmpDir.absolutePath}/%(title)s.%(ext)s")
                    }
                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        runOnUiThread {
                            if (progress > 0) {
                                progressBar.isIndeterminate = false
                                progressBar.progress = progress.toInt()
                                statusText.text = "Téléchargement… ${progress.toInt()} %"
                            }
                        }
                    }
                }

                setStatus("Conversion terminée, enregistrement…")
                val saved = withContext(Dispatchers.IO) {
                    tmpDir.listFiles { f -> f.extension == "mp3" }.orEmpty().map { saveToMusic(it) }
                }
                tmpDir.deleteRecursively()

                if (saved.isEmpty()) {
                    setStatus("Aucun fichier MP3 obtenu.")
                } else {
                    setStatus("✅ Enregistré dans Musique/MusicDownloader :\n${saved.joinToString("\n")}")
                    urlInput.text.clear()
                }
            } catch (e: Exception) {
                setStatus("❌ Erreur : ${e.message}")
            } finally {
                busy = false
                downloadButton.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    // Prépare yt-dlp et ffmpeg, puis met yt-dlp à jour (YouTube change souvent)
    private suspend fun ensureInitialized() {
        if (initialized) return
        setStatus("Préparation (première fois, ça peut prendre un moment)…")
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        }
        setStatus("Mise à jour de yt-dlp…")
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            }
        } catch (e: Exception) {
            // Pas bloquant : on tente quand même avec la version embarquée
        }
        initialized = true
    }

    // Copie le MP3 dans le dossier public Musique pour qu'il apparaisse dans les lecteurs audio
    private fun saveToMusic(file: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MusicDownloader")
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

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
