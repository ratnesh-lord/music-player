package app.unpaused.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        TrackFts::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        FavoriteEntity::class,
        PlayEventEntity::class,
        DiagnosticEventEntity::class,
        FolderEntity::class,
        RadioStationEntity::class,
        LyricsSidecarEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun diagnostics(): DiagnosticsDao
    abstract fun folders(): FolderDao
    abstract fun radio(): RadioDao
    abstract fun lyricsSidecars(): LyricsSidecarDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "unpaused.db")
                // The library is a cache of what's on the device and can always be rebuilt by a scan;
                // user data (playlists, favourites) gets real migrations from version 2 on.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
