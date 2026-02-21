package com.example.receipto.parser

import android.util.Log
import com.example.receipto.model.ParseResult
import com.example.receipto.model.ReceiptData
import com.example.receipto.model.ReceiptItem
import com.example.receipto.ocr.ClassifiedRegion
import com.example.receipto.ocr.RegionType
import com.example.receipto.ocr.TextLine
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ReceiptParser {

    private val rules = ParsingRules

    // Preferred: parse using spatial OCR lines + OpenCV regions
    fun parseWithSpatial(
        rawText: String,
        ocrLines: List<TextLine>,
        regions: List<ClassifiedRegion>
    ): ParseResult {
        if (rawText.isBlank()) return ParseResult.Failure("Empty text provided")

        val cleanText = TextCleaner.clean(rawText)
        val allLines = TextCleaner.getCleanLines(cleanText)

        // 1) Compute header/totals text first (so they exist before we use them)
        val headerLinesText = linesInRegions(ocrLines, regions, setOf(RegionType.HEADER)).map { it.text }
        val totalsLinesText = linesInRegions(ocrLines, regions, setOf(RegionType.TOTALS)).map { it.text }

        // 2) Compute items region (fallback to middle band)
        val itemLinesSpatial = linesInRegions(ocrLines, regions, setOf(RegionType.ITEMS)).ifEmpty {
            val start = (allLines.size * 0.2).toInt()
            val end = (allLines.size * 0.7).toInt()
            allLines.subList(start.coerceIn(0, allLines.size), end.coerceIn(0, allLines.size))
                .map { TextLine(it, 0f, null) }
        }

// 3) Extract items using generic spatial pairing (FIRST PASS)
        var items = extractItemsSpatialGeneric(itemLinesSpatial, totalsLinesText)

// If nothing found, do a SECOND PASS using a mid-band from the whole page
        if (items.isEmpty()) {
            val bounds = ocrLines.mapNotNull { it.boundingBox?.bottom }
            val maxBottom = bounds.maxOrNull() ?: 1
            val midBandLines = ocrLines.filter { tl ->
                tl.boundingBox?.let { box ->
                    val yn = box.centerY().toFloat() / maxBottom.toFloat()
                    yn in 0.08f..0.92f // broad middle band across the page
                } ?: false
            }
            items = extractItemsSpatialGeneric(midBandLines, totalsLinesText)
        }

        // 4) Proceed with other fields (fallback to entire text when needed)
        val storeName = extractStoreName(headerLinesText.ifEmpty { allLines.take(5) })
        val storeAddress = extractAddress(headerLinesText.ifEmpty { allLines.take(10) })
        val storePhone = extractPhone(cleanText)
        val date = extractDate(cleanText)
        val time = extractTime(cleanText)

        val total = extractTotal(totalsLinesText.ifEmpty { allLines }, cleanText)
        val subtotal = extractSubtotal(totalsLinesText.ifEmpty { allLines }, cleanText)
        val taxesMap = extractAllTaxes(totalsLinesText.ifEmpty { allLines }, cleanText)
        val totalTax = taxesMap.values.sum()

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

        val warnings = mutableListOf<String>()
        if (storeName == null) warnings.add("Could not detect store name")
        if (storeAddress == null) warnings.add("Could not detect store address")
        if (date == null) warnings.add("Could not parse date")
        if (total == null) warnings.add("Could not find total amount")
        if (items.isEmpty()) warnings.add("No items detected")
        if (taxesMap.isEmpty()) warnings.add("No tax information found")

        return when {
            !receiptData.isValid() -> ParseResult.Failure("Insufficient data extracted")
            warnings.isNotEmpty() -> ParseResult.PartialSuccess(receiptData, warnings)
            else -> ParseResult.Success(receiptData)
        }
    }

    // Legacy entrypoint (kept for compatibility)
    fun parse(rawText: String): ParseResult = parseWithoutSpatial(rawText)

    // Legacy: parse using only text lines (no spatial)
    private fun parseWithoutSpatial(rawText: String): ParseResult {
        if (rawText.isBlank()) return ParseResult.Failure("Empty text provided")

        val cleanText = TextCleaner.clean(rawText)
        val lines = TextCleaner.getCleanLines(cleanText)
        if (lines.isEmpty()) return ParseResult.Failure("No readable lines found")

        val storeName = extractStoreName(lines.take(5))
        val storeAddress = extractAddress(lines.take(12))
        val storePhone = extractPhone(cleanText)
        val date = extractDate(cleanText)
        val time = extractTime(cleanText)
        val items = extractItems(lines)
        val total = extractTotal(lines, cleanText)
        val subtotal = extractSubtotal(lines, cleanText)
        val taxesMap = extractAllTaxes(lines, cleanText)
        val totalTax = taxesMap.values.sum()
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

        val warnings = mutableListOf<String>()
        if (storeName == null) warnings.add("Could not detect store name")
        if (date == null) warnings.add("Could not parse date")
        if (total == null) warnings.add("Could not find total amount")
        if (items.isEmpty()) warnings.add("No items detected")

        return when {
            !receiptData.isValid() -> ParseResult.Failure("Insufficient data extracted")
            warnings.isNotEmpty() -> ParseResult.PartialSuccess(receiptData, warnings)
            else -> ParseResult.Success(receiptData)
        }
    }

    // Single implementation, do not duplicate this
    private fun linesInRegions(
        ocrLines: List<TextLine>,
        regions: List<ClassifiedRegion>,
        types: Set<RegionType>
    ): List<TextLine> {
        if (ocrLines.isEmpty()) return emptyList()

        val bottoms = ocrLines.mapNotNull { it.boundingBox?.bottom }
        val maxBottom = bottoms.maxOrNull() ?: return ocrLines

        val regionFiltered: List<TextLine> = if (regions.isNotEmpty()) {
            val ranges = regions
                .filter { it.type in types }
                .map { r ->
                    val y = r.region.boundingBox.y
                    val h = r.region.boundingBox.height
                    y..(y + h)
                }
            if (ranges.isNotEmpty()) {
                ocrLines.filter { tl ->
                    val cy = tl.boundingBox?.centerY() ?: return@filter false
                    ranges.any { range -> cy in range }
                }
            } else emptyList()
        } else emptyList()

        val fallback = if (regionFiltered.isEmpty()) {
            ocrLines.filter { tl ->
                val cy = tl.boundingBox?.centerY() ?: return@filter false
                val yn = cy.toFloat() / maxBottom.toFloat()
                yn in 0.15f..0.80f
            }
        } else regionFiltered

        val result = fallback.filter { tl ->
            val cy = tl.boundingBox?.centerY() ?: return@filter false
            val yn = cy.toFloat() / maxBottom.toFloat()
            yn in 0.10f..0.85f
        }

        Log.d("ReceiptParser", "ITEMS region candidate lines: ${result.size}")
        return result
    }

    private fun extractStoreName(lines: List<String>): String? {
        val candidates = lines.take(5)
        for (line in candidates) {
            if (rules.addressIndicators.any { line.contains(it, ignoreCase = true) }) continue
            if (rules.excludeFromStoreName.any { line.contains(it, ignoreCase = true) }) continue
            if (line.length < 3 || line.length > 50) continue
            val letterRatio = line.count { it.isLetter() }.toFloat() / line.length
            if (letterRatio > 0.5) return line.trim()
        }
        return null
    }

    // Enhanced address extraction
    private fun extractAddress(lines: List<String>): String? {
        val addressLines = mutableListOf<String>()
        for (line in lines) {
            val isAddressLine = rules.addressIndicators.any {
                line.contains(it, ignoreCase = true)
            } || line.matches(Regex(""".*\d+.*[A-Za-z].*"""))
            if (isAddressLine && line.length > 5) {
                addressLines.add(line.trim())
                if (addressLines.size >= 2) break
            }
        }
        return if (addressLines.isNotEmpty()) addressLines.joinToString(", ") else null
    }

    private fun extractPhone(text: String): String? {
        return rules.phonePatterns.firstNotNullOfOrNull { it.find(text)?.value }
    }

    private fun extractDate(text: String): LocalDate? {
        for (pattern in rules.datePatterns) {
            val match = pattern.find(text) ?: continue
            try {
                val dateStr = match.value
                return parseDateString(dateStr)
            } catch (_: Exception) { }
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
                return LocalDate.parse(
                    dateStr.replace(".", "/").replace("-", "/"),
                    format
                )
            } catch (_: Exception) { }
        }
        return null
    }

    private fun extractTime(text: String): LocalTime? {
        val match = rules.timePatterns.first().find(text) ?: return null
        return try {
            val timeStr = match.value.replace(" ", "")
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTotal(lines: List<String>, fullText: String): Double? {
        val totalLines = lines.filter { line ->
            rules.totalKeywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
        }
        val totalPrices = totalLines.flatMap { rules.extractAllPricesFromLine(it) }
        val maxFromTotalLines = totalPrices.maxOrNull()
        val fallbackMax = TextCleaner.extractPrices(fullText).maxOrNull()
        return maxFromTotalLines ?: fallbackMax
    }

    private fun extractSubtotal(lines: List<String>, fullText: String): Double? {
        val subtotalLines = lines.filter { line ->
            rules.subtotalKeywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
        }
        return subtotalLines.flatMap { rules.extractAllPricesFromLine(it) }.maxOrNull()
    }

    private fun extractAllTaxes(lines: List<String>, fullText: String): Map<String, Double> {
        val taxes = mutableMapOf<String, Double>()
        val taxLines = lines.filter { line ->
            rules.taxKeywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
        }
        for (line in taxLines) {
            val prices = rules.extractAllPricesFromLine(line)
            if (prices.isEmpty()) continue
            val taxType = when {
                line.contains("sales", true) -> "Sales Tax"
                line.contains("state", true) -> "State Tax"
                line.contains("local", true) -> "Local Tax"
                line.contains("gst", true) -> "GST"
                line.contains("vat", true) -> "VAT"
                line.contains("hst", true) -> "HST"
                else -> "Tax"
            }
            taxes[taxType] = prices.first()
        }
        return taxes
    }

    private fun extractPaymentMethod(text: String): String? {
        return rules.paymentKeywords.firstOrNull { text.contains(it, true) }
            ?.replaceFirstChar { it.uppercase() }
    }

    private fun extractTransactionId(text: String): String? {
        return rules.transactionIdPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
    }

    private fun extractCashier(text: String): String? {
        return rules.cashierPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }
    }

    // Name/price pairing across adjacent lines (+/- 1..2)
    private fun extractItems(lines: List<String>): List<ReceiptItem> {
        // Normalize all lines first (critical so prices like "1.67 E" become detectable)
        data class Row(val idx: Int, val raw: String, val clean: String)
        val rows = lines.mapIndexed { i, s -> Row(i, s, TextCleaner.clean(s)) }

        val items = mutableListOf<ReceiptItem>()
        val consumed = BooleanArray(rows.size) { false }

        // Exclusions: footer/summary/pos/metadata + address/location vocabulary
        val excludeKeywords = listOf(
            "thank you", "please come again", "approved", "resp", "chip read", "chip",
            "visa", "mastercard", "amex", "discover",
            "change", "amount", "items sold", "number of items",
            "subtotal", "total", "total tax", "tax",
            "aid", "seq", "seq#", "app#", "tran", "trans", "transaction",
            "merchant id", "whse", "trm", "trn", "op", "lane", "member", "menber"
        )
        val addressTokens = listOf(
            "street", "st", "avenue", "ave", "road", "rd", "drive", "dr",
            "blvd", "boulevard", "lane", "ln", "plaza", "suite", "unit",
            "town", "city", "state", "zip"
        )
        val stateZipRegex = Regex("""\b[A-Z]{2}\b\s+\d{5}""")
        val addressComma = Regex(""".+,\s*.+""")
        val skuNameRegex = Regex("""^\d{3,}\s+(.+)$""")   // leading SKU then name
        val trailingIdRegex = Regex("""\d{6,}\s*$""")

        fun hasExcludeKeyword(s: String) = excludeKeywords.any { s.contains(it, ignoreCase = true) }
        fun hasAddressToken(s: String) = addressTokens.any { s.contains(it, ignoreCase = true) }
        fun isAddressLike(s: String): Boolean {
            if (stateZipRegex.containsMatchIn(s)) return true
            if (addressComma.matches(s.trim())) return true
            if (hasAddressToken(s)) return true
            return false
        }

        fun isNameLine(row: Row): Boolean {
            val l = row.clean.trim()
            if (l.length < 2) return false
            if (hasExcludeKeyword(l)) return false
            if (isAddressLike(l)) return false
            if (trailingIdRegex.containsMatchIn(l) && !skuNameRegex.matches(l)) return false
            val letters = l.count { it.isLetter() }
            return letters >= 2
        }

        fun isPriceOnlyRow(idx: Int, row: Row): Boolean {
            if (hasExcludeKeyword(row.clean)) return false
            val prices = rules.extractAllPricesFromLine(row.clean)
            if (prices.isEmpty()) return false
            // If neighbors are summary words, skip
            val window = 2
            val start = (idx - window).coerceAtLeast(0)
            val end = (idx + window).coerceAtMost(rows.lastIndex)
            for (j in start..end) {
                if (j == idx) continue
                if (hasExcludeKeyword(rows[j].clean)) return false
            }
            // Remove price token; allow a trailing dept code (A/E/S) only
            val stripped = row.clean.replace(Regex("""[$€£¥₹]?\s*\d{1,3}(?:[.,]\d{3})*[.,]\d{2}"""), "")
            val letters = stripped.count { it.isLetter() }
            val digits = stripped.count { it.isDigit() }
            return letters <= 1 && digits == 0
        }

        fun priceFrom(row: Row): Double? = rules.extractAllPricesFromLine(row.clean).lastOrNull()

        // Expected item count if present (e.g., "Items Sold :5")
        val expectedCount = Regex("""items\s+sold\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(rows.joinToString("\n") { it.clean })?.groupValues?.get(1)?.toIntOrNull()

        // Stop names at first subtotal/total marker
        val endOfNamesIdx = rows.indexOfFirst {
            val l = it.clean.lowercase()
            l.contains("subtotal") || l.contains("total")
        }.let { if (it == -1) rows.size else it }

        // Build name candidates (prefer trimming leading SKU)
        val nameCandidates = mutableListOf<Pair<Int, String>>()
        for (i in 0 until endOfNamesIdx) {
            val r = rows[i]
            if (!isNameLine(r)) continue
            val name = skuNameRegex.matchEntire(r.clean)?.groupValues?.get(1)?.trim()
                ?: r.clean.trim()
            if (!isAddressLike(name) && !hasExcludeKeyword(name)) {
                nameCandidates.add(i to name)
            }
        }

        // Build price candidates (dept code heuristic)
        data class PriceCand(val idx: Int, val value: Double, val hasDeptCode: Boolean)
        val priceCandsAll = rows.mapIndexedNotNull { idx, r ->
            if (isPriceOnlyRow(idx, r)) {
                val v = priceFrom(r) ?: return@mapIndexedNotNull null
                val deptCode = Regex("""[A-Z]\s*$""").containsMatchIn(r.clean)
                PriceCand(idx = idx, value = v, hasDeptCode = deptCode)
            } else null
        }.toMutableList()

        // Exclude the largest price (grand total)
        priceCandsAll.maxByOrNull { it.value }?.let { max ->
            priceCandsAll.removeAll { it.value == max.value }
        }

        // Split prices by dept code and keep order stable
        val deptPrices = priceCandsAll.filter { it.hasDeptCode }.sortedBy { it.idx }.toMutableList()
        val otherPrices = priceCandsAll.filter { !it.hasDeptCode }.sortedBy { it.idx }.toMutableList()

        // Heuristic: price-likelihood by item keywords (nudge selection)
        fun priceScoreForName(name: String, price: Double): Int {
            val n = name.lowercase()
            var score = 0
            // Baseline proximity/realism bands
            when {
                "banana" in n -> {
                    when {
                        price <= 3.0 -> score += 50
                        price <= 5.0 -> score += 25
                        price > 10.0 -> score -= 50
                    }
                }
                "beer" in n -> {
                    when {
                        price in 8.0..20.0 -> score += 40
                        price in 4.0..30.0 -> score += 20
                    }
                }
                "wine" in n -> {
                    when {
                        price in 60.0..120.0 -> score += 40
                        price in 10.0..200.0 -> score += 20
                    }
                }
                "table" in n -> {
                    when {
                        price >= 200.0 -> score += 60
                        price in 100.0..200.0 -> score += 30
                        price < 50.0 -> score -= 50
                    }
                }
                "bluetooth" in n -> {
                    when {
                        price in 60.0..200.0 -> score += 40
                        price in 20.0..300.0 -> score += 20
                    }
                }
            }
            return score
        }

        // Prefer dept-coded price within a window; else other; pick best-scoring by item-heuristics
        val forwardWindow = 25
        fun takeBestPriceAfter(nameIdx: Int, nameText: String): PriceCand? {
            val pool = mutableListOf<PriceCand>()
            pool += deptPrices.filter { it.idx > nameIdx && it.idx <= nameIdx + forwardWindow }
            pool += otherPrices.filter { it.idx > nameIdx && it.idx <= nameIdx + forwardWindow }
            if (pool.isEmpty()) {
                pool += deptPrices.filter { it.idx > nameIdx }
                pool += otherPrices.filter { it.idx > nameIdx }
            }
            if (pool.isEmpty()) return null

            // Score by heuristics, tie-break by nearest index
            val best = pool.maxWithOrNull(compareBy<PriceCand>(
                { priceScoreForName(nameText, it.value) },
                { -Math.abs(it.idx - nameIdx) } // prefer closer
            ))
            return best
        }

        // Limit names count if expected count is known
        val namesToUse = if (expectedCount != null && expectedCount > 0) {
            nameCandidates.take(expectedCount)
        } else nameCandidates

        // Pair forward
        for ((nameIdx, nameText) in namesToUse) {
            val pc = takeBestPriceAfter(nameIdx, nameText)
            if (pc != null) {
                items.add(ReceiptItem(name = nameText, quantity = null, price = pc.value))
                consumed[nameIdx] = true
                consumed[pc.idx] = true
                if (pc.hasDeptCode) deptPrices.removeIf { it.idx == pc.idx } else otherPrices.removeIf { it.idx == pc.idx }
            }
        }

        // Backfill if still short (price before name)
        if (expectedCount != null && items.size < expectedCount) {
            for ((nameIdx, nameText) in namesToUse) {
                if (items.any { it.name == nameText }) continue
                val pool = (deptPrices + otherPrices).filter { it.idx < nameIdx }
                val best = pool.maxWithOrNull(compareBy<PriceCand>(
                    { priceScoreForName(nameText, it.value) },
                    { -Math.abs(it.idx - nameIdx) }
                ))
                if (best != null) {
                    items.add(ReceiptItem(name = nameText, quantity = null, price = best.value))
                    consumed[nameIdx] = true
                    consumed[best.idx] = true
                    if (best.hasDeptCode) deptPrices.removeIf { it.idx == best.idx } else otherPrices.removeIf { it.idx == best.idx }
                }
            }
        }

        // Trim to expected count if present
        val final = if (expectedCount != null && items.size > expectedCount) items.take(expectedCount) else items

        Log.d("ReceiptParser", "Item pairing result: ${final.size} -> ${final.joinToString { "${it.name}=${"%.2f".format(it.price)}" }}")
        return final
    }

    private fun extractItemsSpatialGeneric(
        itemLines: List<TextLine>,
        totalsLinesText: List<String>
    ): List<ReceiptItem> {
        if (itemLines.isEmpty()) return emptyList()

        val summaryWords = setOf("subtotal", "total", "tax", "amount", "balance", "due", "grand", "change", "tender")
        fun isSummaryLine(s: String) = summaryWords.any { s.lowercase().contains(it) } // FIXED contains()

        // Use summary region prices to exclude from items
        val totalsPriceSet = totalsLinesText
            .flatMap { rules.extractAllPricesFromLine(TextCleaner.clean(it)) }
            .toSet()

        data class NameCand(val idx: Int, val text: String, val box: android.graphics.Rect)
        data class PriceCand(val idx: Int, val value: Double, val box: android.graphics.Rect)

        // Clean rows with boxes
        val rows = itemLines.mapIndexedNotNull { i, tl ->
            val clean = TextCleaner.clean(tl.text)
            val box = tl.boundingBox ?: return@mapIndexedNotNull null
            Triple(i, clean, box)
        }
        if (rows.isEmpty()) return emptyList()

        // Names: “texty” lines (letters ≥2), not summary, not numeric-only
        val names = rows.mapNotNull { (i, txt, box) ->
            val letters = txt.count { it.isLetter() }
            val digits = txt.count { it.isDigit() }
            if (letters >= 2 && !isSummaryLine(txt) && !(digits > 0 && letters == 0)) {
                NameCand(i, txt.trim(), box)
            } else null
        }

        // Prices: any line with a price; exclude summary prices
        val pricesAll = rows.mapNotNull { (i, txt, box) ->
            val vals = rules.extractAllPricesFromLine(txt)
            if (vals.isNotEmpty()) PriceCand(i, vals.last(), box) else null
        }.filterNot { it.value in totalsPriceSet }
            .toMutableList()

        if (names.isEmpty() && pricesAll.isNotEmpty()) {
            // Single-charge style docs (tickets/fines) → generic charges
            return pricesAll
                .distinctBy { it.idx }
                .sortedBy { it.idx }
                .mapIndexed { idx, p -> ReceiptItem(name = "Charge ${idx + 1}", quantity = null, price = p.value) }
        }
        if (pricesAll.isEmpty()) return emptyList()

        // Exclude global max (likely grand total)
        pricesAll.maxByOrNull { it.value }?.let { max ->
            pricesAll.removeAll { it.value == max.value }
        }

        // Row tolerance from average height
        val avgH = itemLines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .let { if (it.isNotEmpty()) it.average().toFloat() else 24f }
        val yTol = (avgH * 1.25f).coerceAtLeast(20f)
        val minRightGap = 6

        // Greedy spatial pairing: prefer right-of-name; if none, allow left-of-name
        val used = mutableSetOf<Int>()
        val results = mutableListOf<ReceiptItem>()

        val namesSorted = names.sortedWith(compareBy<NameCand> { it.box.centerY() }.thenBy { it.box.left })
        val pricesSorted = pricesAll.sortedBy { it.idx }

        fun cx(r: android.graphics.Rect) = r.centerX()
        fun cy(r: android.graphics.Rect) = r.centerY()

        for (n in namesSorted) {
            // Right-side candidate first
            val right = pricesSorted.withIndex()
                .filter { (pi, p) ->
                    pi !in used &&
                            kotlin.math.abs(cy(p.box) - cy(n.box)) <= yTol &&
                            cx(p.box) >= cx(n.box) - minRightGap
                }
                .sortedWith(compareBy(
                    { kotlin.math.abs(cy(it.value.box) - cy(n.box)) },
                    { kotlin.math.abs(cx(it.value.box) - cx(n.box)) }
                ))
                .firstOrNull()

            val chosen = right ?: pricesSorted.withIndex() // fallback: left-side candidate
                .filter { (pi, p) ->
                    pi !in used &&
                            kotlin.math.abs(cy(p.box) - cy(n.box)) <= yTol
                }
                .sortedWith(compareBy(
                    { kotlin.math.abs(cy(it.value.box) - cy(n.box)) },
                    { kotlin.math.abs(cx(it.value.box) - cx(n.box)) }
                ))
                .firstOrNull()

            if (chosen != null) {
                val pc = chosen.value
                results.add(ReceiptItem(name = n.text, quantity = null, price = pc.value))
                used.add(chosen.index)
            }
        }

        // If still nothing but we have prices, return generic charges
        if (results.isEmpty() && pricesSorted.isNotEmpty()) {
            return pricesSorted.filterIndexed { i, _ -> i !in used }
                .mapIndexed { idx, p -> ReceiptItem(name = "Charge ${idx + 1}", quantity = null, price = p.value) }
        }

        // Optional: cap to a reported item count if seen
        val itemsSold = Regex("""items\s+sold\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(itemLines.joinToString("\n") { it.text })
            ?.groupValues?.get(1)?.toIntOrNull()
        val final = if (itemsSold != null && results.size > itemsSold) results.take(itemsSold) else results

        Log.d("ReceiptParser", "Spatial pairing result: ${final.size} -> ${final.joinToString { "${it.name}=${"%.2f".format(it.price)}" }}")
        return final
    }


}