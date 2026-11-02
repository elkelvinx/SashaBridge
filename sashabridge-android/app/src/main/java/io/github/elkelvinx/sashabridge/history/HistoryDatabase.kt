package io.github.elkelvinx.sashabridge.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The history database, `history.db`, through Room.
 *
 * Room is a layer over SQLite, not another database: the file, its format and its size are
 * SQLite's own, plus one internal row (`room_master_table`). What Room adds is in the code:
 * every query is checked when the app is built, rows become Kotlin objects without copying
 * columns by hand, each schema version is exported to `app/schemas/` and its migration is
 * tested, queries can be watched as a `Flow`, and none runs on the main thread.
 *
 * Version 1 was a single table, `plays`, written with `SQLiteOpenHelper`; [HistoryMigrations]
 * turns it into the tables of `HistoryTables.kt`.
 *
 * It runs on the phone's own SQLite through Room's driver API ([AndroidSQLiteDriver]); the
 * tests use the same API with a SQLite for the JVM.
 */
@Database(
    entities = [SongEntity::class, PlayEntity::class, SongMonthEntity::class, AppDayEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class HistoryDatabase : RoomDatabase() {

    abstract fun dao(): HistoryDao

    companion object {

        /** File name, the same as version 1's, so the migration finds it. */
        const val NAME = "history.db"

        /** Opens the database of the app, migrating it if it is older. */
        fun open(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, NAME)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(HistoryMigrations.FROM_1_TO_2)
                .build()
    }
}
