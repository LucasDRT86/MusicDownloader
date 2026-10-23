package com.lucas.musicdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private lateinit var downloadCard: MaterialCardView
    private lateinit var downloadTitle: TextView
    private lateinit var downloadMessage: TextView
    private lateinit var downloadProgress: LinearProgressIndicator

    private var busy = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        downloadCard = findViewById(R.id.downloadCard)
        downloadTitle = findViewById(R.id.downloadTitle)
        downloadMessage = findViewById(R.id.downloadMessage)
        downloadProgress = findViewById(R.id.downloadProgress)

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
        downloadButton.setOnClickListener { startDownload() }

        // Affiche l'avancement du service de téléchargement
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.status.collect { showDownloadStatus(it) }
            }
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

    // Le téléchargement tourne dans un service : il continue si on quitte l'app
    private fun startDownload() {
        val url = currentUrl ?: return
        val playlist = isPlaylistSelected()
        val info = if (playlist) playlistInfo else videoInfo
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Pour voir la progression dans les notifications (le téléchargement marche même si refusé)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val alreadyRunning = DownloadState.status.value.active
        DownloadService.start(this, url, playlist, info?.title.orEmpty())
        Toast.makeText(
            this,
            if (alreadyRunning) "Ajouté à la file d'attente" else "Téléchargement lancé, tu peux quitter l'app",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showDownloadStatus(status: DownloadStatus) {
        if (status.message.isEmpty() && !status.active) {
            downloadCard.visibility = View.GONE
            return
        }
        downloadCard.visibility = View.VISIBLE
        downloadTitle.text = status.title
        downloadMessage.text =
            if (status.queued > 0) "${status.message}\n+${status.queued} en attente" else status.message
        if (status.active) {
            downloadProgress.visibility = View.VISIBLE
            if (status.progress < 0) {
                downloadProgress.isIndeterminate = true
            } else {
                downloadProgress.isIndeterminate = false
                downloadProgress.setProgressCompat(status.progress, true)
            }
        } else {
            downloadProgress.visibility = View.GONE
        }
    }

    // ---------- Outils ----------

    private suspend fun ensureInitialized() = Ytdl.ensureInitialized(this) { setStatus(it) }

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

    private fun shortError(e: Exception) = Ytdl.shortError(e)

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
