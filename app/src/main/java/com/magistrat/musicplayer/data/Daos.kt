package com.magistrat.musicplayer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun all(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    suspend fun allOnce(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE youtubeId = :ytId LIMIT 1")
    suspend fun byYoutubeId(ytId: String): Track?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE LIMIT 100")
    suspend fun search(q: String): List<Track>

    @Query("SELECT * FROM tracks WHERE title = :title COLLATE NOCASE AND artist = :artist COLLATE NOCASE LIMIT 1")
    suspend fun byTitleArtist(title: String, artist: String): Track?

    @Insert
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Delete
    suspend fun delete(track: Track)
}

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount " +
            "FROM playlists p ORDER BY p.name COLLATE NOCASE"
    )
    fun allWithCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun allOnce(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): Playlist?

    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount " +
            "FROM playlists p WHERE p.name LIKE '%' || :q || '%' ORDER BY p.name COLLATE NOCASE LIMIT 50"
    )
    suspend fun search(q: String): List<PlaylistWithCount>

    @Query("SELECT * FROM playlists WHERE sourceUrl = :url LIMIT 1")
    suspend fun bySourceUrl(url: String): Playlist?

    @Insert
    suspend fun insert(p: Playlist): Long

    @Update
    suspend fun update(p: Playlist)

    @Delete
    suspend fun delete(p: Playlist)

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.id = pt.trackId " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position"
    )
    fun tracks(playlistId: Long): Flow<List<Track>>

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.id = pt.trackId " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position"
    )
    suspend fun tracksOnce(playlistId: Long): List<Track>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(pt: PlaylistTrack)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: Long)

    @Insert
    suspend fun insertAll(items: List<PlaylistTrack>)

    @Transaction
    suspend fun setOrder(playlistId: Long, trackIds: List<Long>) {
        clear(playlistId)
        insertAll(trackIds.mapIndexed { i, id -> PlaylistTrack(playlistId, id, i) })
    }
}

@Dao
interface AudiobookDao {
    @Query(
        "SELECT b.*, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id) AS chapterCount, " +
            "(SELECT COALESCE(SUM(c.durationMs), 0) FROM chapters c WHERE c.bookId = b.id) AS totalMs, " +
            "COALESCE((SELECT bm.positionMs + COALESCE((SELECT SUM(c2.durationMs) FROM chapters c2 " +
            "   WHERE c2.bookId = b.id AND c2.position < (SELECT c3.position FROM chapters c3 WHERE c3.id = bm.itemId)), 0) " +
            "  FROM bookmarks bm WHERE bm.sourceType = 'AUDIOBOOK' AND bm.sourceId = b.id AND bm.isAuto = 1 LIMIT 1), 0) AS listenedMs " +
            "FROM audiobooks b ORDER BY b.title COLLATE NOCASE"
    )
    fun allWithCount(): Flow<List<AudiobookWithCount>>

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    fun observe(id: Long): Flow<Audiobook?>

    @Query("SELECT * FROM audiobooks WHERE id = :id")
    suspend fun get(id: Long): Audiobook?

    @Insert
    suspend fun insert(b: Audiobook): Long

    @Update
    suspend fun update(b: Audiobook)

    @Delete
    suspend fun delete(b: Audiobook)

    @Insert
    suspend fun insertChapters(chapters: List<Chapter>)

    @Insert
    suspend fun insertChapter(chapter: Chapter): Long

    @Query("SELECT * FROM audiobooks WHERE title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE LIMIT 50")
    suspend fun search(q: String): List<Audiobook>

    @Query(
        "SELECT c.*, b.title AS bookTitle, b.coverPath AS bookCover FROM chapters c " +
            "INNER JOIN audiobooks b ON b.id = c.bookId " +
            "WHERE c.title LIKE '%' || :q || '%' ORDER BY b.title COLLATE NOCASE, c.position LIMIT 100"
    )
    suspend fun searchChapters(q: String): List<ChapterHit>

    @Query("SELECT * FROM audiobooks WHERE sourceUrl = :url LIMIT 1")
    suspend fun bySourceUrl(url: String): Audiobook?

    @Query("SELECT youtubeId FROM chapters WHERE bookId = :bookId AND youtubeId IS NOT NULL")
    suspend fun youtubeIds(bookId: Long): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM chapters WHERE bookId = :bookId")
    suspend fun maxChapterPosition(bookId: Long): Int

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    fun chapters(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun chaptersOnce(bookId: Long): List<Chapter>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE sourceType = :type AND sourceId = :id AND isAuto = 1 LIMIT 1")
    suspend fun auto(type: SourceType, id: Long): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE sourceType = :type AND sourceId = :id AND isAuto = 1 LIMIT 1")
    fun observeAuto(type: SourceType, id: Long): Flow<Bookmark?>

    @Query("SELECT * FROM bookmarks WHERE sourceType = :type AND sourceId = :id AND isAuto = 0 ORDER BY createdAt DESC")
    fun manual(type: SourceType, id: Long): Flow<List<Bookmark>>

    @Query(
        "SELECT b.*, COALESCE(p.name, a.title) AS sourceTitle, COALESCE(p.coverPath, a.coverPath) AS sourceCover " +
            "FROM bookmarks b " +
            "LEFT JOIN playlists p ON b.sourceType = 'PLAYLIST' AND p.id = b.sourceId " +
            "LEFT JOIN audiobooks a ON b.sourceType = 'AUDIOBOOK' AND a.id = b.sourceId " +
            "WHERE b.isAuto = 1 AND (p.id IS NOT NULL OR a.id IS NOT NULL) " +
            "ORDER BY b.createdAt DESC LIMIT 12"
    )
    fun recent(): Flow<List<RecentSource>>

    @Query(
        "SELECT b.*, COALESCE(p.name, a.title) AS sourceTitle, COALESCE(p.coverPath, a.coverPath) AS sourceCover " +
            "FROM bookmarks b " +
            "LEFT JOIN playlists p ON b.sourceType = 'PLAYLIST' AND p.id = b.sourceId " +
            "LEFT JOIN audiobooks a ON b.sourceType = 'AUDIOBOOK' AND a.id = b.sourceId " +
            "WHERE b.isAuto = 1 AND (p.id IS NOT NULL OR a.id IS NOT NULL) " +
            "ORDER BY b.createdAt DESC LIMIT 12"
    )
    suspend fun recentOnce(): List<RecentSource>

    @Insert
    suspend fun insert(b: Bookmark): Long

    @Update
    suspend fun update(b: Bookmark)

    @Delete
    suspend fun delete(b: Bookmark)

    @Query("DELETE FROM bookmarks WHERE sourceType = :type AND sourceId = :id")
    suspend fun deleteForSource(type: SourceType, id: Long)

    @Transaction
    suspend fun upsertAuto(b: Bookmark) {
        val existing = auto(b.sourceType, b.sourceId)
        if (existing == null) insert(b.copy(id = 0, isAuto = true))
        else update(b.copy(id = existing.id, isAuto = true))
    }
}
