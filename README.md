# MusicDownloader

Petite app Android perso : colle un lien YouTube (ou partage une vidéo depuis l'app YouTube), vérifie l'aperçu (miniature, titre, durée) puis télécharge l'audio en MP3 dans `Musique/MusicDownloader`. Les playlists entières sont prises en charge et rangées dans un sous-dossier à leur nom.

Basée sur [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp + ffmpeg embarqués).

## Récupérer l'APK

À chaque push sur `main`, GitHub Actions construit l'APK et le publie dans l'onglet **Releases** du dépôt.
Sur le téléphone : ouvre la dernière release, télécharge `MusicDownloader.apk`, puis installe-le
(Android demandera d'autoriser l'installation depuis le navigateur).

## Utilisation

- Ouvre l'app, colle le lien (ou touche l'icône presse-papiers), appuie sur « Voir l'aperçu » puis « Télécharger en MP3 ».
- Ou dans YouTube : **Partager** → **Music Downloader**, l'aperçu s'affiche et tu choisis de télécharger.
- Pour un lien vers une vidéo dans une playlist, un interrupteur permet de prendre toute la playlist.

Android 10 minimum. Usage personnel uniquement, dans le respect des droits d'auteur et des conditions d'utilisation de YouTube.
