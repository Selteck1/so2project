package com.selteck.so2project.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StickerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sticker: StickerEntity): Long

    @Query("SELECT * FROM sticker_catalog ORDER BY name ASC, visualVariant ASC")
    suspend fun getAll(): List<StickerEntity>

    @Query("SELECT * FROM sticker_catalog WHERE marketPrice >= :minimumPrice ORDER BY marketPrice DESC")
    suspend fun getAtOrAbove(minimumPrice: Double): List<StickerEntity>

    @Query("SELECT COUNT(*) FROM sticker_catalog")
    suspend fun count(): Int
}
