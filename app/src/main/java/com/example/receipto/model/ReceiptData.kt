package com.example.receipto.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * Complete receipt data structure
 */
data class ReceiptData(
    val storeName: String? = null,
    val storeAddress: String? = null,
    val storePhone: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val items: List<ReceiptItem>

    = emptyList(),
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val paymentMethod: String? = null,
    val transactionId: String? = null,
    val cashier: String? = null,
    val rawText: String = ""
) {
    /**
     * Check if receipt has minimum required data
     */
    fun isValid(): Boolean {
        return storeName != null || total != null || items.isNotEmpty()
    }

    /**
     * Get

    confidence score (0.0 to 1.0)
     */
    fun getConfidenceScore(): Float {
        var score = 0f

        // Store name (15%)
        if (storeName != null && storeName.length >= 3) score += 0.15f

        // Date (10%)
        if (date != null) score += 0.10f

        // Total (35% - most important!)
        if (total != null && total > 0) score += 0.35f

        // Items (25%)
        when {
            items.size >= 3 -> score += 0.25f
            items.size >= 1 -> score += 0.15f
        }

        // Tax (10%)
        if (tax != null && tax > 0) score += 0.10f

        // Subtotal validation (5% bonus if subtotal + tax ≈ total)
        if (subtotal != null && tax != null && total != null) {
            val calculatedTotal = subtotal + tax
            val diff = kotlin.math.abs(calculatedTotal - total)
            if (diff < 0.50) {  // Within 50 cents
                score += 0.05f
            }
        }

        return score.coerceIn(0f, 1f)
    }

}

/**

Individual item on receipt
 */
data class ReceiptItem(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPrice: Double? = null,
    val price: Double,
    val taxable: Boolean = true
)

/**

Result of parsing operation
 */
sealed class ParseResult {
    data class Success(val data: ReceiptData) : ParseResult()
    data class PartialSuccess(val data: ReceiptData, val warnings: List<String>) : ParseResult()
    data class Failure(val error: String) : ParseResult()
}
