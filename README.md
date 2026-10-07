# MusicPlayer

Android-App (Kotlin, Jetpack Compose, Media3) für Musik und Hörbücher.

## Funktionen

- **YouTube → MP3**: Link einfügen (oder in der YouTube-App *Teilen → MusicPlayer*), die App lädt das Audio
  mit yt-dlp + ffmpeg herunter und speichert es als MP3. Das Vorschaubild wird automatisch als Cover übernommen.
  Optional landet der Song direkt in einer Playlist.
- **Ganze YouTube-Playlists**: Bei einem Playlist-Link „Ganze YouTube-Playlist laden“ anhaken – alle Videos werden
  nacheinander geladen und als Playlist (in der Original-Reihenfolge) angelegt.
- **Spotify-Playlists übernehmen**: Spotify-Link (Playlist, Album oder Song) einfügen oder in der Spotify-App
  *Teilen → MusicPlayer*. Die Titelliste wird gelesen (öffentliche Playlists, kein Spotify-Konto nötig, bis ca. 100 Titel),
  jeder Song auf YouTube gesucht (Bewertung nach Dauer, Interpret, offiziellen Kanälen; Live/Cover/Remix werden gemieden)
  und als MP3 in derselben Reihenfolge in eine Playlist gelegt – mit Spotify-Cover. Über ⟳ in der Playlist werden später
  neue Songs der Spotify-Playlist ergänzt.
- **Hörbücher/Hörspiele von YouTube**: Im Download-Tab „Speichern als: Hörbuch“ wählen (oder Hörbücher → + → „Von YouTube laden“).
  Eine Playlist wird zu einem Hörbuch, jedes Video zu einem Kapitel (Sprache mit 96 kbit/s – spart Speicher).
  Über das ↻-Symbol im Hörbuch werden später nur **neue Folgen** nachgeladen.
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
- **Weiterhören** ganz oben im Songs-Tab: die zuletzt gehörten Playlists/Hörbücher mit Stand – ein Tipp setzt fort.
  Hörbücher springen dabei 10 s zurück, damit man wieder reinkommt.
- **Hörbuch-Fortschritt** in der Liste („43 % gehört, noch 3:12 h“, „fertig gehört ✓“).
- **Schlaftimer** im Player (Mond-Symbol): 5–90 Minuten mit sanftem Ausblenden oder „Ende des Kapitels/Titels“.
- **Warteschlange**: ⋮ → „Als Nächstes spielen“ / „Zur Warteschlange hinzufügen“; im Player über das Listen-Symbol
  ansehen, umsortieren (Griff ziehen) und Einträge entfernen.
- **Drag & Drop** in Playlists: am Griff rechts ziehen.
- **App-Updates**: Die App prüft beim Start die GitHub-Releases und bietet neue Versionen direkt zum Installieren an
  (oder manuell im Download-Tab „Nach App-Updates suchen“). Beim ersten Mal muss Android die Installation aus der App erlauben.
- **Homescreen-Widget** (lange auf den Startbildschirm drücken → Widgets → MusicPlayer): zeigt die laufende bzw. zuletzt
  gehörte Playlist/Hörbuch mit Cover und Stand; ▶ setzt direkt dort fort, ohne die App zu öffnen. ⏮ ⏭ springen.
  Kopfhörer-Play-Taste und die Android-Mediensteuerung setzen ebenfalls am letzten Stand fort.
- **Lautstärke angleichen** (ffmpeg loudnorm) und **SponsorBlock** (Werbung, Nicht-Musik-Teile in Musikvideos
  herausschneiden) beim Download – beides im Download-Tab unter „Optionen“ abschaltbar.
- **Equalizer** im Player (Wellen-Symbol): Voreinstellungen Normal, Bass, Sprache, Klassik, Pop, Rock, Höhen
  oder eigene Einstellung pro Band.
- **Kapitelmarken**: Lange YouTube-Videos mit Kapiteln werden als Hörbuch in echte Kapitel aufgeteilt;
  m4b/m4a-Hörbücher mit eingebetteten Kapiteln (Nero oder QuickTime) ebenso.
- **Mehrfachauswahl** in der Songliste (lange drücken): mehrere Songs zur Playlist / Warteschlange oder löschen.
- **Suche über alles** (Lupe im Songs-Tab): Songs, Playlists, Hörbücher und Kapitel.
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
