package com.example.loc.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY id DESC")
    fun getAllFavorites(): LiveData<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY id DESC")
    suspend fun getAllFavoritesList(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Update
    suspend fun update(favorite: FavoriteEntity)

    @Delete
    suspend fun delete(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}
