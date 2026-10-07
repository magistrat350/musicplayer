package com.magistrat.musicplayer.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    /** file:// oder content:// URI der Audiodatei */
    val uri: String,
    /** Absoluter Pfad zum Cover-Bild (im App-Speicher) oder null */
    val coverPath: String? = null,
    val durationMs: Long = 0,
    val youtubeId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Spotify-Playlist, aus der sie uebernommen wurde (fuer "Mit Spotify abgleichen") */
    val sourceUrl: String? = null,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

data class PlaylistWithCount(
    @Embedded val playlist: Playlist,
    val trackCount: Int,
)

@Entity(tableName = "audiobooks")
data class Audiobook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** YouTube-Playlist/-Video, aus dem das Hoerbuch geladen wurde (fuer "Neue Folgen laden") */
    val sourceUrl: String? = null,
)

data class AudiobookWithCount(
    @Embedded val book: Audiobook,
    val chapterCount: Int,
    /** Gesamtdauer aller Kapitel */
    val totalMs: Long,
    /** Gehoerte Zeit laut automatischem Lesezeichen */
    val listenedMs: Long,
)

/** Eintrag fuer "Weiterhoeren": letzter Stand einer Playlist / eines Hoerbuchs. */
data class RecentSource(
    @Embedded val bookmark: Bookmark,
    val sourceTitle: String,
    val sourceCover: String?,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(entity = Audiobook::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId")],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val uri: String,
    val durationMs: Long = 0,
    val position: Int,
    val youtubeId: String? = null,
    /** Abschnitt innerhalb der Datei (Kapitelmarken eines langen Videos / einer m4b-Datei); null = ganze Datei */
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/** Suchtreffer in Hoerbuch-Kapiteln */
data class ChapterHit(
    @Embedded val chapter: Chapter,
    val bookTitle: String,
    val bookCover: String?,
)

/** Woher die aktuelle Wiedergabeliste stammt. */
enum class SourceType { LIBRARY, PLAYLIST, AUDIOBOOK }

/**
 * Lesezeichen innerhalb einer Playlist / eines Hoerbuchs.
 * isAuto = true: der automatisch gespeicherte "letzte Stand" (genau einer pro Quelle).
 * isAuto = false: manuell gesetzte Lesezeichen (beliebig viele).
 */
@Entity(tableName = "bookmarks", indices = [Index("sourceType", "sourceId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType,
    val sourceId: Long,
    /** Track-ID bzw. Kapitel-ID */
    val itemId: Long,
    val itemIndex: Int,
    val itemTitle: String,
    val positionMs: Long,
    val label: String? = null,
    val isAuto: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)
