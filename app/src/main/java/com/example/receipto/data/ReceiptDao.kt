package com.example.receipto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insert(receipt: ReceiptEntity)

    @Query("SELECT * FROM ReceiptEntity ORDER BY id DESC")
    fun getAllReceipts(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM ReceiptEntity WHERE id = :id")
    suspend fun getReceiptById(id: Int): ReceiptEntity?
}