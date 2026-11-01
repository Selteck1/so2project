package com.selteck.so2project.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StickerEntity::class], version = 1, exportSchema = false)
abstract class So2Database : RoomDatabase() {
    abstract fun stickerDao(): StickerDao
    companion object {
        @Volatile private var INSTANCE: So2Database? = null
        fun get(context: Context): So2Database =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    So2Database::class.java,
                    "so2-project.db"
                ).build().also { INSTANCE = it }
            }
    }
}
