package com.jellowbeanz.json.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Conversation::class, Message::class], version = 2, exportSchema = false)
abstract class JsonDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: JsonDatabase? = null

        fun get(context: Context): JsonDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                JsonDatabase::class.java,
                "json.db",
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}
