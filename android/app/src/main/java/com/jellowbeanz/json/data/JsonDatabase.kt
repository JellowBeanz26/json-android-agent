package com.jellowbeanz.json.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Conversation::class, Message::class], version = 1, exportSchema = false)
abstract class JsonDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: JsonDatabase? = null

        fun get(context: Context): JsonDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                JsonDatabase::class.java,
                "json.db",
            ).build().also { INSTANCE = it }
        }
    }
}
