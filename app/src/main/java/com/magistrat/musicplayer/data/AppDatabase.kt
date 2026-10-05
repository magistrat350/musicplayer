package com.magistrat.musicplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromSource(s: SourceType): String = s.name

    @TypeConverter
    fun toSource(s: String): SourceType = SourceType.valueOf(s)
}

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, Audiobook::class, Chapter::class, Bookmark::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun audiobooks(): AudiobookDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "musicplayer.db").build()
    }
}
