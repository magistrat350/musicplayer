# MusicPlayer

Android-App (Kotlin, Jetpack Compose, Media3) für Musik und Hörbücher.

## Funktionen

- **YouTube → MP3**: Link einfügen (oder in der YouTube-App *Teilen → MusicPlayer*), die App lädt das Audio
  mit yt-dlp + ffmpeg herunter und speichert es als MP3. Das Vorschaubild wird automatisch als Cover übernommen.
  Optional landet der Song direkt in einer Playlist.
- **Bild pro Song**: Songs → ⋮ → *Bearbeiten / Bild* → eigenes Bild aus der Galerie wählen (auch für Playlists und Hörbücher).
- **Playlists**: erstellen, umbenennen, Songs hinzufügen/entfernen/sortieren, eigenes Cover.
- **Hörbücher**: einen Ordner mit Kapiteln oder einzelne Dateien (mp3, m4b, m4a, …) importieren.
  Kapitel werden natürlich sortiert (Kapitel 2 vor Kapitel 10). Wiedergabegeschwindigkeit 0,75×–2×, ±10/30 s springen.
- **Lesezeichen**
  - *Automatisch*: Für jede Playlist und jedes Hörbuch wird der letzte Stand (Titel/Kapitel + Position) gespeichert –
    beim Wechsel zu einer anderen Playlist / einem anderen Hörbuch, beim Pausieren, beim Titelwechsel und alle 10 s.
    In der Playlist bzw. dem Hörbuch steht dann **„Fortsetzen: … · 12:34“**.
  - *Manuell*: Im Player oben rechts auf das Lesezeichen-Symbol tippen (optional mit Bezeichnung).
    Die Lesezeichen erscheinen in der jeweiligen Playlist / im Hörbuch und lassen sich antippen oder löschen.
- Hintergrundwiedergabe mit Steuerung in der Benachrichtigung und auf dem Sperrbildschirm.
- Vorhandene MP3s vom Handy importieren (Songs → Ordnersymbol).

## APK bekommen

Jeder Push baut die App über GitHub Actions (`.github/workflows/build-apk.yml`).
Die fertige APK hängt am jeweiligen **Release** (Repository → *Releases* → `build-N`) und zusätzlich als Artifact am Workflow-Lauf.

1. Auf dem Handy `MusicPlayer-arm64-v8a.apk` aus dem neuesten Release herunterladen.
2. Datei öffnen → Installation aus unbekannten Quellen für den Browser/Dateimanager erlauben → *Installieren*.
3. Updates: einfach die neuere APK genauso installieren – die Daten bleiben erhalten
   (alle Builds werden mit demselben Schlüssel `app/release.keystore` signiert).

Hinweis: Der Signaturschlüssel liegt bewusst im Repository, damit Updates ohne Einrichtung funktionieren.
Wer das nicht möchte, kann eigene Werte über die Umgebungsvariablen `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` und `KEY_PASSWORD` setzen (dann aber vorher die alte App deinstallieren).

## Hinweise

- Wenn YouTube-Downloads plötzlich fehlschlagen: im Download-Tab **„yt-dlp aktualisieren“** tippen
  (passiert zusätzlich automatisch einmal am Tag).
- Bitte nur Inhalte herunterladen, für die du die Rechte hast bzw. die für den privaten Gebrauch erlaubt sind.
- Lokal bauen: Android Studio öffnen oder `./gradlew assembleRelease` (benötigt Android SDK, JDK 17).
