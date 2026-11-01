package com.selteck.so2project.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sticker_catalog", indices = [Index(value = ["name", "visualVariant"], unique = true)])
data class StickerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val marketPrice: Double,
    val visualVariant: String = "default",
    val imagePath: String? = null,
    val visualHash: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
