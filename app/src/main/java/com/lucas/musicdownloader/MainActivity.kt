package com.lucas.musicdownloader

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Ce qu'on affiche dans la carte d'aperçu
    private data class MediaInfo(
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val isPlaylist: Boolean,
        val count: Int,
    )

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var previewButton: MaterialButton
    private lateinit var previewCard: MaterialCardView
    private lateinit var thumbnail: ImageView
    private lateinit var badge: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var playlistSwitch: MaterialSwitch
    private lateinit var downloadButton: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var statusText: TextView

    private val initMutex = Mutex()
    private var initialized = false
    private var busy = false

    // Résultat de la dernière analyse
    private var currentUrl: String? = null
    private var videoInfo: MediaInfo? = null
    private var playlistInfo: MediaInfo? = null
    private val thumbnails = mutableMapOf<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlLayout = findViewById(R.id.urlLayout)
        urlInput = findViewById(R.id.urlInput)
        previewButton = findViewById(R.id.previewButton)
        previewCard = findViewById(R.id.previewCard)
        thumbnail = findViewById(R.id.thumbnail)
        badge = findViewById(R.id.badge)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        playlistSwitch = findViewById(R.id.playlistSwitch)
        downloadButton = findViewById(R.id.downloadButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        urlLayout.setEndIconOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Le presse-papiers est vide", Toast.LENGTH_SHORT).show()
            } else {
                val url = extractUrl(text)
                urlInput.setText(url)
                analyze(url)
            }
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                analyze(urlInput.text.toString())
                true
            } else {
                false
            }
        }
        previewButton.setOnClickListener { analyze(urlInput.text.toString()) }
        playlistSwitch.setOnCheckedChangeListener { _, _ -> showSelectedInfo() }
        downloadButton.setOnClickListener {
            currentUrl?.let { download(it, isPlaylistSelected()) }
        }

        // On prépare yt-dlp dès l'ouverture pour que l'aperçu soit plus rapide
        lifecycleScope.launch {
            try {
                ensureInitialized()
            } catch (e: Exception) {
                setStatus("❌ Impossible de préparer l'app : ${e.message}")
            }
        }

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
        val url = extractUrl(sharedText)
        urlInput.setText(url)
        analyze(url)
    }

    // YouTube partage parfois "Titre https://youtu.be/xxx" : on garde juste le lien
    private fun extractUrl(text: String): String =
        Regex("https?://\\S+").find(text)?.value ?: text.trim()

    // ---------- Aperçu ----------

    private fun analyze(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Colle d'abord un lien", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Patiente, une opération est en cours", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true, indeterminate = true)
        previewCard.visibility = View.GONE
        currentUrl = null
        videoInfo = null
        playlistInfo = null
        thumbnails.clear()

        lifecycleScope.launch {
            try {
                ensureInitialized()
                setStatus("Analyse du lien…")

                val hasList = url.contains("list=")
                val hasVideo = Regex("[?&]v=|youtu\\.be/|/shorts/").containsMatchIn(url)

                if (hasList && hasVideo) {
                    // Lien vers une vidéo dans une playlist : on propose les deux
                    val video = async { fetchInfo(url, playlist = false) }
                    val list = async { runCatching { fetchInfo(url, playlist = true) }.getOrNull() }
                    videoInfo = video.await()
                    playlistInfo = list.await()?.takeIf { it.isPlaylist }
                } else {
                    val info = fetchInfo(url, playlist = hasList)
                    if (info.isPlaylist) playlistInfo = info else videoInfo = info
                }

                currentUrl = url
                playlistSwitch.setOnCheckedChangeListener(null)
                playlistSwitch.isChecked = videoInfo == null
                playlistSwitch.setOnCheckedChangeListener { _, _ -> showSelectedInfo() }
                playlistSwitch.visibility =
                    if (videoInfo != null && playlistInfo != null) View.VISIBLE else View.GONE
                playlistSwitch.text = "Télécharger toute la playlist (${playlistInfo?.count ?: 0} titres)"

                showSelectedInfo()
                previewCard.visibility = View.VISIBLE
                setStatus("")
            } catch (e: Exception) {
                setStatus("❌ Impossible de lire ce lien.\n${shortError(e)}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun isPlaylistSelected(): Boolean =
        playlistInfo != null && (videoInfo == null || playlistSwitch.isChecked)

    private fun showSelectedInfo() {
        val info = (if (isPlaylistSelected()) playlistInfo else videoInfo) ?: return
        titleText.text = info.title
        subtitleText.text = info.subtitle
        badge.visibility = if (info.isPlaylist) View.VISIBLE else View.GONE
        badge.text = "▶ ${info.count} titres"
        downloadButton.text =
            if (info.isPlaylist) "Télécharger la playlist en MP3" else "Télécharger en MP3"

        val thumbUrl = info.thumbnailUrl
        if (thumbUrl == null) {
            thumbnail.setImageDrawable(null)
            return
        }
        if (thumbnails.containsKey(thumbUrl)) {
            thumbnail.setImageBitmap(thumbnails[thumbUrl])
            return
        }
        thumbnail.setImageDrawable(null)
        lifecycleScope.launch {
            val bitmap = loadBitmap(thumbUrl)
            thumbnails[thumbUrl] = bitmap
            // On n'affiche que si c'est toujours l'élément sélectionné
            val selected = if (isPlaylistSelected()) playlistInfo else videoInfo
            if (selected?.thumbnailUrl == thumbUrl) thumbnail.setImageBitmap(bitmap)
        }
    }

    // Demande à yt-dlp les infos du lien, sans rien télécharger
    private suspend fun fetchInfo(url: String, playlist: Boolean): MediaInfo =
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption(if (playlist) "--yes-playlist" else "--no-playlist")
            }
            val json = JSONObject(YoutubeDL.getInstance().execute(request).out)
            val uploader = json.optString("uploader").ifBlank { json.optString("channel") }

            if (json.optString("_type") == "playlist") {
                val entries = json.optJSONArray("entries")
                val count = json.optInt("playlist_count", 0).takeIf { it > 0 } ?: (entries?.length() ?: 0)
                val first = entries?.optJSONObject(0)
                MediaInfo(
                    title = json.optString("title").ifBlank { "Playlist" },
                    subtitle = listOf("Playlist · $count titres", uploader).filter { it.isNotBlank() }.joinToString(" · "),
                    thumbnailUrl = bestThumbnail(json) ?: first?.let { bestThumbnail(it) },
                    isPlaylist = true,
                    count = count,
                )
            } else {
                MediaInfo(
                    title = json.optString("title").ifBlank { "Sans titre" },
                    subtitle = listOf(uploader, formatDuration(json.optInt("duration", 0)))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    thumbnailUrl = bestThumbnail(json),
                    isPlaylist = false,
                    count = 1,
                )
            }
        }

    private fun bestThumbnail(json: JSONObject): String? {
        json.optString("thumbnail").takeIf { it.isNotBlank() }?.let { return it }
        val list = json.optJSONArray("thumbnails")
        if (list != null && list.length() > 0) {
            list.optJSONObject(list.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // Miniature standard de YouTube à partir de l'identifiant de la vidéo
        return json.optString("id").takeIf { it.length == 11 }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    // ---------- Téléchargement ----------

    private fun download(url: String, playlist: Boolean) {
        if (busy) return
        setBusy(true, indeterminate = true)
        // Évite que l'écran s'éteigne pendant une longue playlist
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            try {
                ensureInitialized()
                setStatus("Téléchargement…")
                val tmpDir = File(cacheDir, "downloads").apply {
                    deleteRecursively()
                    mkdirs()
                }

                val error = withContext(Dispatchers.IO) {
                    val output = if (playlist) {
                        "${tmpDir.absolutePath}/%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s"
                    } else {
                        "${tmpDir.absolutePath}/%(title)s.%(ext)s"
                    }
                    val request = YoutubeDLRequest(url).apply {
                        addOption(if (playlist) "--yes-playlist" else "--no-playlist")
                        addOption("-x") // audio uniquement
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                        addOption("--add-metadata")
                        addOption("--windows-filenames") // évite les caractères refusés par Android
                        if (playlist) addOption("--ignore-errors") // une vidéo indisponible ne bloque pas le reste
                        addOption("-o", output)
                    }
                    var item = ""
                    try {
                        YoutubeDL.getInstance().execute(request) { progress, _, line ->
                            Regex("Downloading item (\\d+) of (\\d+)").find(line)?.let {
                                item = "Titre ${it.groupValues[1]}/${it.groupValues[2]} · "
                            }
                            runOnUiThread {
                                if (progress > 0) {
                                    progressBar.isIndeterminate = false
                                    progressBar.setProgressCompat(progress.toInt(), true)
                                }
                                statusText.text = "${item}Téléchargement… ${progress.toInt().coerceAtLeast(0)} %"
                            }
                        }
                        null
                    } catch (e: Exception) {
                        // Pour une playlist, certaines vidéos ont pu réussir : on enregistre quand même
                        e
                    }
                }

                setStatus("Enregistrement dans Musique…")
                progressBar.isIndeterminate = true
                val saved = withContext(Dispatchers.IO) {
                    tmpDir.walkTopDown()
                        .filter { it.isFile && it.extension == "mp3" }
                        .sortedBy { it.path }
                        .map { saveToMusic(it, tmpDir) }
                        .toList()
                }
                tmpDir.deleteRecursively()

                when {
                    saved.isEmpty() && error != null ->
                        setStatus("❌ Échec du téléchargement.\n${shortError(error)}")
                    saved.isEmpty() ->
                        setStatus("Aucun fichier MP3 obtenu.")
                    playlist -> {
                        val failed = if (error != null) "\n⚠️ Certains titres n'ont pas pu être téléchargés." else ""
                        setStatus("✅ ${saved.size} titres enregistrés dans Musique/MusicDownloader$failed")
                    }
                    else ->
                        setStatus("✅ Enregistré dans Musique/MusicDownloader :\n${saved.joinToString("\n")}")
                }
            } catch (e: Exception) {
                setStatus("❌ Erreur : ${shortError(e)}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBusy(false)
            }
        }
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

    // ---------- Outils ----------

    // Prépare yt-dlp et ffmpeg, puis met yt-dlp à jour (YouTube change souvent)
    private suspend fun ensureInitialized() = initMutex.withLock {
        if (initialized) return@withLock
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
        setStatus("")
    }

    private fun setBusy(value: Boolean, indeterminate: Boolean = false) {
        busy = value
        previewButton.isEnabled = !value
        downloadButton.isEnabled = !value
        playlistSwitch.isEnabled = !value
        if (value) {
            progressBar.isIndeterminate = indeterminate
            progressBar.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.GONE
        }
    }

    // Les erreurs de yt-dlp sont longues : on garde la ligne utile
    private fun shortError(e: Exception): String {
        val message = e.message ?: return e.javaClass.simpleName
        return message.lines().lastOrNull { it.contains("ERROR") }?.trim() ?: message.lines().first().take(300)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
