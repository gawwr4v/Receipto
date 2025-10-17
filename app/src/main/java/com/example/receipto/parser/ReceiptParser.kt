package com.example.receipto.parser

import com.example.receipto.model.ParseResult
import com.example.receipto.model.ReceiptData
import com.example.receipto.model.ReceiptItem
import com.example.receipto.ocr.ClassifiedRegion
import com.example.receipto.ocr.RegionType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ReceiptParser {

    private val rules = ParsingRules

    /**
     * Enhanced parsing with region information from OpenCV
     */
    fun parseWithRegions(rawText: String, regions: List<ClassifiedRegion>): ParseResult {
        if (rawText.isBlank()) {
            return ParseResult.Failure("Empty text provided")
        }

        val cleanText = TextCleaner.clean(rawText)
        val lines = TextCleaner.getCleanLines(cleanText)

        if (lines.isEmpty()) {
            return ParseResult.Failure("No readable lines found")
        }

        val warnings = mutableListOf<String>()

        // Use regions for better parsing
        val headerLines = getLinesInRegion(lines, regions, RegionType.HEADER)
        val itemLines = getLinesInRegion(lines, regions, RegionType.ITEMS)
        val totalsLines = getLinesInRegion(lines, regions, RegionType.TOTALS)

        val storeName = extractStoreName(headerLines.ifEmpty { lines.take(5) })
        val storeAddress = extractAddress(headerLines.ifEmpty { lines.take(10) })
        val storePhone = extractPhone(cleanText)
        val date = extractDate(cleanText)
        val time = extractTime(cleanText)

        // Enhanced item extraction using ITEMS region
        val items = extractItems(itemLines.ifEmpty { lines })

        val total = extractTotal(totalsLines.ifEmpty { lines }, cleanText)
        val subtotal = extractSubtotal(totalsLines.ifEmpty { lines }, cleanText)

        // NEW: Extract multiple tax types
        val taxes = extractAllTaxes(totalsLines.ifEmpty { lines }, cleanText)
        val totalTax = taxes.values.sum()

        val paymentMethod = extractPaymentMethod(cleanText)
        val transactionId = extractTransactionId(cleanText)
        val cashier = extractCashier(cleanText)

        val receiptData = ReceiptData(
            storeName = storeName,
            storeAddress = storeAddress,
            storePhone = storePhone,
            date = date,
            time = time,
            items = items,
            subtotal = subtotal,
            tax = totalTax,
            total = total,
            paymentMethod = paymentMethod,
            transactionId = transactionId,
            cashier = cashier,
            rawText = rawText
        )

        if (storeName == null) warnings.add("Could not detect store name")
        if (storeAddress == null) warnings.add("Could not detect store address")
        if (date == null) warnings.add("Could not parse date")
        if (total == null) warnings.add("Could not find total amount")
        if (items.isEmpty()) warnings.add("No items detected")
        if (taxes.isEmpty()) warnings.add("No tax information found")

        return when {
            !receiptData.isValid() -> ParseResult.Failure("Insufficient data extracted")
            warnings.isNotEmpty() -> ParseResult.PartialSuccess(receiptData, warnings)
            else -> ParseResult.Success(receiptData)
        }
    }

    // Fallback for backward compatibility
    fun parse(rawText: String): ParseResult {
        return parseWithRegions(rawText, emptyList())
    }

    /**
     * Get lines that belong to a specific region type
     */
    private fun getLinesInRegion(
        lines: List<String>,
        regions: List<ClassifiedRegion>,
        regionType: RegionType
    ): List<String> {
        if (regions.isEmpty()) return emptyList()

        val targetRegions = regions.filter { it.type == regionType }
        if (targetRegions.isEmpty()) return emptyList()



// For simplicity, returning all lines (region bounding boxes would need more complex matching)
// will enhance this later to match lines to bounding boxes
        return when (regionType) {
            RegionType.HEADER -> lines.take((lines.size * 0.2).toInt().coerceAtLeast(3))
            RegionType.ITEMS -> {
                val start = (lines.size * 0.2).toInt()
                val end = (lines.size * 0.7).toInt()
                lines.subList(start.coerceIn(0, lines.size), end.coerceIn(0, lines.size))
            }
            RegionType.TOTALS -> lines.takeLast((lines.size * 0.3).toInt().coerceAtLeast(5))
            RegionType.FOOTER -> lines.takeLast((lines.size * 0.15).toInt().coerceAtLeast(2))
            RegionType.UNKNOWN -> emptyList()
        }
    }

    private fun extractStoreName(lines: List<String>): String? {
        val candidates = lines.take(5)

        for (line in candidates) {
            if (rules.addressIndicators.any { line.contains(it, ignoreCase = true) }) continue
            if (rules.excludeFromStoreName.any { line.contains(it, ignoreCase = true) }) continue
            if (line.length < 3 || line.length > 50) continue

            val letterRatio = line.count { it.isLetter() }.toFloat() / line.length
            if (letterRatio > 0.5) {
                return line.trim()
            }
        }

        return null
    }

    /**
     * ENHANCED: Better address detection
     */
    private fun extractAddress(lines: List<String>): String? {
        val addressLines = mutableListOf<String>()

        for (line in lines) {
            val isAddressLine = rules.addressIndicators.any {
                line.contains(it, ignoreCase = true)
            } || line.matches(Regex(""".*\d+.*[A-Za-z].*""")) // Has numbers and letters

            if (isAddressLine && line.length > 5) {
                addressLines.add(line.trim())
                if (addressLines.size >= 2) break
            }
        }

        return if (addressLines.isNotEmpty()) {
            addressLines.joinToString(", ")
        } else null
    }

    private fun extractPhone(text: String): String? {
        return rules.phonePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.value
        }
    }

    private fun extractDate(text: String): LocalDate? {
        for (pattern in rules.datePatterns) {
            val match = pattern.find(text) ?: continue

            return try {
                val dateStr = match.value
                parseDateString(dateStr)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun parseDateString(dateStr: String): LocalDate? {
        val formats = listOf(
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
        )

        for (format in formats) {
            try {
                return LocalDate.parse(dateStr.replace(".", "/").replace("-", "/"), format)
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    private fun extractTime(text: String): LocalTime? {
        val timePattern = rules.timePatterns.first()
        val match = timePattern.find(text) ?: return null

        return try {
            val timeStr = match.value.replace(" ", "")
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ENHANCED: Better item extraction
     */
    private fun extractItems(lines: List<String>): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()

        for (line in lines) {
            if (!rules.isLikelyItemLine(line)) continue

            val prices = rules.extractAllPricesFromLine(line)
            if (prices.isEmpty()) continue

            // Skip if line contains total/tax keywords
            if (rules.totalKeywords.any { line.contains(it, ignoreCase = true) }) continue
            if (rules.taxKeywords.any { line.contains(it, ignoreCase = true) }) continue
            if (rules.subtotalKeywords.any { line.contains(it, ignoreCase = true) }) continue

            val price = prices.last()
            val name = rules.extractItemName(line) ?: continue

            if (name.length < 2) continue

            // Extract quantity
            val quantityPattern = Regex("""(\d+\.?\d*)\s*(?:x|X|@|lb|kg|oz|ea)""", RegexOption.IGNORE_CASE)
            val quantityMatch = quantityPattern.find(name)
            val quantity = quantityMatch?.groupValues?.get(1)?.toDoubleOrNull()

            items.add(
                ReceiptItem(
                    name = name.trim(),
                    quantity = quantity,
                    price = price
                )
            )
        }

        return items
    }

    private fun extractTotal(lines: List<String>, fullText: String): Double? {
        val totalLines = lines.filter { line ->
            rules.totalKeywords.any { keyword ->
                line.contains(keyword, ignoreCase = true)
            }
        }

        val totalPrices = totalLines.flatMap { line ->
            rules.extractAllPricesFromLine(line)
        }

        val maxFromTotalLines = totalPrices.maxOrNull()
        val fallbackMax = TextCleaner.extractPrices(fullText).maxOrNull()

        return maxFromTotalLines ?: fallbackMax
    }

    private fun extractSubtotal(lines: List<String>, fullText: String): Double? {
        val subtotalLines = lines.filter { line ->
            rules.subtotalKeywords.any { keyword ->
                line.contains(keyword, ignoreCase = true)
            }
        }

        return subtotalLines.flatMap { line ->
            rules.extractAllPricesFromLine(line)
        }.maxOrNull()
    }

    /**
     * NEW: Extract multiple tax types (Sales Tax, State Tax, Local Tax, etc.)
     */
    private fun extractAllTaxes(lines: List<String>, fullText: String): Map<String, Double> {
        val taxes = mutableMapOf<String, Double>()

        val taxLines = lines.filter { line ->
            rules.taxKeywords.any { keyword ->
                line.contains(keyword, ignoreCase = true)
            }
        }

        for (line in taxLines) {
            val prices = rules.extractAllPricesFromLine(line)
            if (prices.isEmpty()) continue

            // Determine tax type
            val taxType = when {
                line.contains("sales", ignoreCase = true) -> "Sales Tax"
                line.contains("state", ignoreCase = true) -> "State Tax"
                line.contains("local", ignoreCase = true) -> "Local Tax"
                line.contains("gst", ignoreCase = true) -> "GST"
                line.contains("vat", ignoreCase = true) -> "VAT"
                line.contains("hst", ignoreCase = true) -> "HST"
                else -> "Tax"
            }

            taxes[taxType] = prices.first()
        }

        return taxes
    }

    private fun extractPaymentMethod(text: String): String? {
        return rules.paymentKeywords.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }?.replaceFirstChar { it.uppercase() }
    }

    private fun extractTransactionId(text: String): String? {
        return rules.transactionIdPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.get(1)
        }
    }

    private fun extractCashier(text: String): String? {
        return rules.cashierPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.trim()
        }
    }

}

