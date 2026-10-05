package com.magistrat.musicplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromSource(s: SourceType): String = s.name

    @TypeConverter
    fun toSource(s: String): SourceType = SourceType.valueOf(s)
}

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrack::class, Audiobook::class, Chapter::class, Bookmark::class],
    version = 2,
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
            Room.databaseBuilder(context, AppDatabase::class.java, "musicplayer.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: Hoerbuecher von YouTube (Quelle + Video-ID je Kapitel) */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN sourceUrl TEXT")
                db.execSQL("ALTER TABLE chapters ADD COLUMN youtubeId TEXT")
            }
        }
    }
}
