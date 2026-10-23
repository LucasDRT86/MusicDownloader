# MusicDownloader

Petite app Android perso : colle une URL YouTube (ou partage une vidéo depuis l'app YouTube) et l'audio est enregistré en MP3 dans `Musique/MusicDownloader`.

Basée sur [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp + ffmpeg embarqués).

## Récupérer l'APK

À chaque push sur `main`, GitHub Actions construit l'APK et le publie dans l'onglet **Releases** du dépôt.
Sur le téléphone : ouvre la dernière release, télécharge `MusicDownloader.apk`, puis installe-le
(Android demandera d'autoriser l'installation depuis le navigateur).

## Utilisation

- Ouvre l'app, colle le lien, appuie sur « Télécharger en MP3 ».
- Ou dans YouTube : **Partager** → **Music Downloader**, le téléchargement démarre tout seul.

Android 10 minimum. Usage personnel uniquement, dans le respect des droits d'auteur et des conditions d'utilisation de YouTube.
