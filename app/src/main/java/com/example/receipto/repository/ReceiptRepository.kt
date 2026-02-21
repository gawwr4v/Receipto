package com.example.receipto.repository

import com.example.receipto.data.ReceiptDao
import com.example.receipto.data.ReceiptEntity
import kotlinx.coroutines.flow.Flow

class ReceiptRepository(private val receiptDao: ReceiptDao) {

    val allReceipts: Flow<List<ReceiptEntity>> = receiptDao.getAllReceipts()

    suspend fun insert(receipt: ReceiptEntity) {
        receiptDao.insert(receipt)
    }

    suspend fun getReceiptById(id: Int): ReceiptEntity? {
        return receiptDao.getReceiptById(id)
    }
}