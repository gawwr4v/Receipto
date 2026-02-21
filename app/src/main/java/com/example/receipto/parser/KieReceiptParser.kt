package com.example.receipto.parser

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.receipto.kie.KieRuntime
import com.example.receipto.kie.OcrToken as KieOcrToken
import com.example.receipto.model.ReceiptData
import com.example.receipto.model.ReceiptItem
import com.example.receipto.ocr.OcrToken
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * KieReceiptParser.kt
 * 
 * WHY THIS CLASS EXISTS:
 * Receipts are chaotic. ML Kit just spits out raw bounding boxes everywhere. 
 * If you try to parse them line-by-line right in the UI, you get a 1000-line 
 * monster. 
 * 
 * This class is the deterministic brain. It takes those raw tokens, groups them 
 * physically (y-axis merging), runs them through the offline KIE model, and 
 * then aggressively uses spatial rules (like "find the rightmost price on this line") 
 * when the KIE model fails.
 */
class KieReceiptParser(private val context: Context) {

    data class UiTok(val text: String, val box: Rect)
    data class LTok(val text: String, val box: Rect, val tag: String)

    fun parseReceipt(
        rawTokens: List<OcrToken>,
        pageWidth: Int,
        pageHeight: Int
    ): ReceiptData? {
        if (rawTokens.isEmpty() || pageWidth <= 0 || pageHeight <= 0) return null

        val mergedUi = reorderAndMergeTokens(rawTokens, pageWidth, pageHeight)
        
        val kie = KieRuntime(context)
        val kieTokens = mergedUi.map { KieOcrToken(it.text, it.box) }
        val encoded = kie.encode(kieTokens, pageWidth, pageHeight)
        val tags = try {
            kie.infer(encoded)
        } catch (e: Exception) {
            Log.e("KIE", "KIE inference failed: ${e.message}", e)
            return null
        }

        // Diagnostics
        val labelCounts = tags.groupingBy { it }.eachCount()
        Log.d("KIE_DEBUG", "Tag counts by label: $labelCounts")

        val receipt = assembleReceiptFromKie(mergedUi, tags, pageHeight)
        Log.d(
            "KIE_DEBUG",
            "Assembled: store=${receipt.storeName}, items=${receipt.items.size}, subtotal=${receipt.subtotal}, tax=${receipt.tax}, total=${receipt.total}, payment=${receipt.paymentMethod}"
        )
        return receipt
    }

    internal fun parseLocalDateFromText(text: String): LocalDate? {
        val finders = listOf(
            Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b"""),
            Regex("""\b\d{1,2}-\d{1,2}-\d{2,4}\b"""),
            Regex("""\b\d{4}-\d{1,2}-\d{1,2}\b"""),
            Regex("""\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{2,4}\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+\d{2,4}\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b""")
        )
        val candidate = finders.firstNotNullOfOrNull { it.find(text)?.value } ?: return null

        val patterns = listOf(
            "M/d/uuuu", "MM/dd/uuuu", "M/d/uu", "MM/dd/uu",
            "M-d-uuuu", "MM-dd-uuuu", "M-d-uu", "MM-dd-uu",
            "uuuu-M-d", "uuuu-MM-dd",
            "d.M.uuuu", "dd.MM.uuuu", "d.M.uu", "dd.MM.uu",
            "MMM d, uuuu", "MMMM d, uuuu", "MMM d uuuu", "MMMM d uuuu",
            "d MMM uuuu", "d MMMM uuuu"
        )

        for (p in patterns) {
            try {
                return LocalDate.parse(candidate, DateTimeFormatter.ofPattern(p, Locale.US))
            } catch (_: Exception) {}
            try {
                val cleaned = candidate.replace(",", "")
                return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern(p, Locale.US))
            } catch (_: Exception) {}
        }
        return null
    }

    internal fun parseLocalTimeFromText(text: String): LocalTime? {
        val finders = listOf(
            Regex("""\b\d{1,2}:\d{2}:\d{2}\s?(AM|PM)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}:\d{2}\s?(AM|PM)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}:\d{2}:\d{2}\b"""),
            Regex("""\b\d{1,2}:\d{2}\b""")
        )
        val candidate = finders.firstNotNullOfOrNull { it.find(text)?.value } ?: return null
        val candUpper = candidate.uppercase(Locale.US)

        val patterns = listOf(
            "h:mm:ss a", "hh:mm:ss a", "h:mm a", "hh:mm a",
            "H:mm:ss", "HH:mm:ss", "H:mm", "HH:mm"
        )

        for (p in patterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.US)
                return if (p.contains('a')) LocalTime.parse(candUpper, fmt) else LocalTime.parse(candidate, fmt)
            } catch (_: Exception) {}
        }
        return null
    }

    internal fun groupTokensIntoLines(tokens: List<UiTok>, pageHeight: Int): List<MutableList<UiTok>> {
        if (tokens.isEmpty()) return emptyList()

        // 1. Calculate a dynamic row tolerance using Median Height of all text tokens
        val heights = tokens.map { it.box.height() }.sorted()
        val medianHeightPx = if (heights.isNotEmpty()) heights[heights.size / 2].toFloat() else 0f
        val normalizedMedianHeight = medianHeightPx / pageHeight.toFloat()
        
        // A physical line is strictly bounded by 50% of the median character height
        val lineThresh = (normalizedMedianHeight * 0.5f).coerceAtLeast(0.015f)

        data class Tn(val tok: UiTok, val yCenter: Float)
        val tn = tokens.map {
            val yc = ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat()
            Tn(it, yc.coerceIn(0f, 1f))
        }.sortedBy { it.yCenter }

        val lines = mutableListOf<MutableList<UiTok>>()
        var cur = mutableListOf<Tn>()
        var currentLineCenterY = 0f

        for (t in tn) {
            if (cur.isEmpty()) {
                cur.add(t)
                currentLineCenterY = t.yCenter
            } else {
                // strict comparison against the baseline of the line to prevent cascading drift
                if (kotlin.math.abs(t.yCenter - currentLineCenterY) <= lineThresh) {
                    cur.add(t)
                } else {
                    lines.add(cur.map { it.tok }.toMutableList())
                    cur.clear()
                    cur.add(t)
                    currentLineCenterY = t.yCenter
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.map { it.tok }.toMutableList())
        lines.forEach { it.sortBy { tok -> tok.box.left } }
        return lines
    }

    private fun isNumericLike(s: String): Boolean {
        if (s.isEmpty()) return false
        var numeric = 0
        for (ch in s) if (ch.isDigit() || ch == '.' || ch == ',' || ch == '$') numeric++
        return numeric * 2 >= s.length
    }

    private fun mergeLineTokens(line: MutableList<UiTok>, pageWidth: Int): MutableList<UiTok> {
        if (line.isEmpty()) return line
        val merged = mutableListOf<UiTok>()
        var curText = java.lang.StringBuilder(line[0].text)
        var curBox = Rect(line[0].box)
        fun extendRect(a: Rect, b: Rect) =
            Rect(
                kotlin.math.min(a.left,b.left), kotlin.math.min(a.top,b.top),
                kotlin.math.max(a.right,b.right), kotlin.math.max(a.bottom,b.bottom)
            )

        for (i in 1 until line.size) {
            val next = line[i]
            val gapPx = next.box.left - curBox.right
            val last = curText.toString().trim()
            val nextTxt = next.text.trim()
            val gapFrac = if (isNumericLike(last) || isNumericLike(nextTxt)) 0.07f else 0.025f
            val gapThreshPx = (gapFrac * pageWidth).toInt()
            if (gapPx <= gapThreshPx && gapPx >= -gapThreshPx) {
                curText.append(" ").append(next.text)
                curBox = extendRect(curBox, next.box)
            } else {
                merged.add(UiTok(curText.toString(), Rect(curBox)))
                curText = java.lang.StringBuilder(next.text)
                curBox = Rect(next.box)
            }
        }
        merged.add(UiTok(curText.toString(), Rect(curBox)))
        return merged
    }

    private fun reorderAndMergeTokens(
        rawTokens: List<OcrToken>,
        pageWidth: Int,
        pageHeight: Int
    ): List<UiTok> {
        val base = rawTokens.map { UiTok(it.text, it.boundingBox) }
        val lines = groupTokensIntoLines(base, pageHeight)
        val mergedLines = lines.map { mergeLineTokens(it, pageWidth) }
        return mergedLines.flatten()
    }

    private fun parseAmountString(raw: String): Double? {
        val s0 = raw.trim()
        val s = s0.replace(Regex("""[^\d,.\-]"""), "")
        if (s.isEmpty()) return null

        val hasComma = s.contains(',')
        val hasDot = s.contains('.')

        val normalized = when {
            hasComma && !hasDot -> s.replace(".", "").replace(',', '.') // EU: 1.234,56 -> 1234.56
            hasDot -> s.replace(",", "") // US: 1,234.56 -> 1234.56
            else -> s
        }
        return normalized.toDoubleOrNull()
    }

    private fun isPriceLikeStrict(raw: String): Boolean {
        val s = raw.trim().replace(" ", "")
        val us = Regex("""^[$€£]?\d{1,3}(?:,\d{3})*(?:\.\d{1,2})$|^[$€£]?\d+\.\d{1,2}$""")
        val eu = Regex("""^\d{1,3}(?:\.\d{3})*(?:,\d{1,2})$|^\d+,\d{1,2}$""")
        return s.matches(us) || s.matches(eu)
    }

    private fun parseAmountsInText(raw: String): List<Double> {
        val s = raw.replace('\u00A0', ' ').replace(Regex("""([.,])\s+(\d{1,2})(?!\d)"""), "$1$2")
        val pat = Regex("""(?<!\w)(?:[$€£]\s*)?\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})(?!\w)""")
        return pat.findAll(s)
            .mapNotNull { m -> parseAmountString(m.value) }
            .filter { it < 1_000_000.0 }
            .toList()
    }

    private fun fallbackStoreFromHeader(merged: List<UiTok>, pageHeight: Int): String? {
        val lines = groupTokensIntoLines(merged, pageHeight)
        if (lines.isEmpty()) return null
    
        val lineCenters = lines.map { line ->
            val cy = line.map { ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat() }.average().toFloat()
            line to cy
        }.sortedBy { it.second }
    
        val (firstLine, firstY) = lineCenters.first()
        val secondLine = lineCenters.drop(1).firstOrNull { (_, y) ->
            val dy = y - firstY
            dy >= 0f && dy <= 0.035f
        }?.first
    
        fun keepStoreToken(t: String): Boolean {
            val s = t.trim()
            if (s.isEmpty()) return false
            if (s.contains('@')) return false
            if (Regex("""\b\d{3,}(-\d+)?\b""").containsMatchIn(s)) return false
            if (Regex("""\b(AVE|ST|RD|BLVD|DR|LANE|LN|HWY|WAY|PL|PINE|DRIVE)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return false
            val letters = s.count { it.isLetter() }
            val digits = s.count { it.isDigit() }
            val ratio = letters.toFloat() / s.length
            return ratio >= 0.5f && letters >= digits
        }
    
        val cleaned1 = firstLine.map { it.text }.filter(::keepStoreToken)
        val cleaned2 = secondLine?.map { it.text }?.filter(::keepStoreToken).orEmpty()
        val joined = (cleaned1 + cleaned2).joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return joined.takeIf { it.length >= 3 }
    }

    private fun extractQuantityFromLine(line: List<UiTok>): Int? {
        if (line.isEmpty()) return null
        val text = line.joinToString(" ") { it.text }.lowercase(Locale.getDefault())

        val patterns = listOf(
            Regex("""\b(?:qty|quantity)\s*[:#-]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\s*[x×]\s*\d""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\s*(?:pcs|pc|units?)\b""", RegexOption.IGNORE_CASE)
        )

        for (pat in patterns) {
            val m = pat.find(text) ?: continue
            val q = m.groupValues.drop(1).firstOrNull()?.toIntOrNull()
            if (q != null && q in 1..999) return q
        }
        val fallback = Regex("""^\s*(\d{1,3})\s+\S+""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return fallback?.takeIf { it in 2..999 }
    }

    private fun rightmostPriceOnLine(line: List<UiTok>): Double? {
        data class Cand(val xRight: Int, val amount: Double)
        val cands = mutableListOf<Cand>()
        for (i in line.indices) {
            val tok = line[i]
            val amounts = parseAmountsInText(tok.text)
            when {
                amounts.isNotEmpty() -> cands += Cand(tok.box.right, amounts.last())
                isPriceLikeStrict(tok.text) -> parseAmountString(tok.text)?.let { cands += Cand(tok.box.right, it) }
                // Allow isolated integers if they are the VERY LAST token on a physical line (More Retail Indian receipts)
                i == line.lastIndex && tok.text.trim().matches(Regex("""^[$€£]?\d{1,4}$""")) -> {
                    parseAmountString(tok.text)?.let { cands += Cand(tok.box.right, it) }
                }
            }
        }
        return cands.maxByOrNull { it.xRight }?.amount
    }

    private fun rightwardPriceForItem(line: List<UiTok>, itemTok: UiTok): Double? {
        data class Cand(val dx: Int, val amount: Double)
        val yTop = itemTok.box.top
        val yBot = itemTok.box.bottom
        val cands = mutableListOf<Cand>()
        for (tok in line) {
            if (tok.box.left <= itemTok.box.right) continue
            val overlap = (minOf(yBot, tok.box.bottom) - maxOf(yTop, tok.box.top)).coerceAtLeast(0)
            val denom = (maxOf(yBot, tok.box.bottom) - minOf(yTop, tok.box.top)).coerceAtLeast(1)
            val overlapRatio = overlap.toFloat() / denom.toFloat()
            if (overlapRatio < 0.5f) continue

            val amounts = parseAmountsInText(tok.text)
            val amt = when {
                amounts.isNotEmpty() -> amounts.first()
                isPriceLikeStrict(tok.text) -> parseAmountString(tok.text)
                else -> null
            } ?: continue
            cands += Cand(tok.box.left - itemTok.box.right, amt)
        }
        return cands.minByOrNull { it.dx }?.amount
    }
    
    private fun paymentFromKieText(text: String): String? {
        if (text.isBlank()) return null
        val brandMap = mapOf(
            "VISA" to "VISA", "MASTERCARD" to "MASTERCARD", "MASTER CARD" to "MASTERCARD",
            "MC" to "MASTERCARD", "AMEX" to "AMEX", "AMERICAN EXPRESS" to "AMEX",
            "DISC" to "DISCOVER", "DISCOVER" to "DISCOVER", "DEBIT" to "DEBIT", "CREDIT" to "CREDIT"
        )
        val brandPat = Regex("""\b(VISA|MASTERCARD|MASTER\s*CARD|MC|AMEX|AMERICAN\s*EXPRESS|DISC(?:OVER)?|DEBIT|CREDIT)\b""", RegexOption.IGNORE_CASE)
        val last4Pats = listOf(
            Regex("""(?:[*xX•#]{2,}[-\s]*){2,}\s*([0-9]{4})\b"""),
            Regex("""\b(?:ending\s+in|ends\s+with)\s*([0-9]{4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b([0-9]{4})\b""")
        )

        val brandMatch = brandPat.find(text)
        if (brandMatch != null) {
            val rawBrand = brandMatch.groupValues[1].uppercase(Locale.getDefault())
            val brand = brandMap.entries.firstOrNull { rawBrand.contains(it.key) }?.value ?: rawBrand
            var last4: String? = null
            for (pat in last4Pats) {
                val m = pat.find(text)
                if (m != null) {
                    val g = m.groupValues.lastOrNull()
                    if (g != null && g.length == 4 && g.all { it.isDigit() }) {
                        last4 = g
                        break
                    }
                }
            }
            return if (last4 != null) "$brand •••• $last4" else brand
        }
        val m = Regex("""(?:[*xX•#]{2,}[-\s]*){2,}\s*([0-9]{4})\b""").find(text)
        return m?.let { "CARD •••• ${it.groupValues[1]}" }
    }

    private fun extractPaymentMethod(merged: List<UiTok>, pageHeight: Int): String? {
        fun centerY(r: Rect) = ((r.top + r.bottom) * 0.5f) / pageHeight.toFloat()
        fun isFooter(r: Rect) = centerY(r) > 0.6f
    
        val footer = merged.filter { isFooter(it.box) }
        if (footer.isEmpty()) return null
    
        val brandMap = mapOf(
            "VISA" to "VISA", "MASTERCARD" to "MASTERCARD", "MASTER CARD" to "MASTERCARD",
            "MC" to "MASTERCARD", "AMEX" to "AMEX", "AMERICAN EXPRESS" to "AMEX",
            "DISC" to "DISCOVER", "DISCOVER" to "DISCOVER", "DEBIT" to "DEBIT", "CREDIT" to "CREDIT"
        )
        val brandPat = Regex("""\b(VISA|MASTERCARD|MASTER\s*CARD|MC|AMEX|AMERICAN\s*EXPRESS|DISC(?:OVER)?|DEBIT|CREDIT)\b""", RegexOption.IGNORE_CASE)
        val last4Pats = listOf(
            Regex("""(?:[*xX•#]{2,}[-\s]*){2,}\s*([0-9]{4})\b"""),
            Regex("""\b(?:ending\s+in|ends\s+with)\s*([0-9]{4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b([0-9]{4})\b""")
        )
    
        val texts = footer.map { it.text }
        for (i in texts.indices) {
            val window = (i..kotlin.math.min(i + 4, texts.lastIndex)).joinToString(" ") { texts[it] }
            val brandMatch = brandPat.find(window)
            if (brandMatch != null) {
                val rawBrand = brandMatch.groupValues[1].uppercase(Locale.getDefault())
                val brand = brandMap.entries.firstOrNull { rawBrand.contains(it.key) }?.value ?: rawBrand
                var last4: String? = null
                for (pat in last4Pats) {
                    val m = pat.find(window)
                    if (m != null) {
                        val g = m.groupValues.lastOrNull()
                        if (g != null && g.length == 4 && g.all { it.isDigit() }) {
                            last4 = g
                            break
                        }
                    }
                }
                return if (last4 != null) "$brand •••• $last4" else brand
            }
        }
    
        val generic = footer.joinToString(" ") { it.text }
        val m = Regex("""(?:[*xX•#]{2,}[-\s]*){2,}\s*([0-9]{4})\b""").find(generic)
        return m?.let { "CARD •••• ${it.groupValues[1]}" }
    }

    internal fun assembleReceiptFromKie(
        merged: List<UiTok>,
        tags: List<String>,
        pageHeight: Int
    ): ReceiptData {

        fun centerY(r: Rect) = ((r.top + r.bottom) * 0.5f) / pageHeight.toFloat()
        fun isHeader(r: Rect) = centerY(r) < 0.25f
        fun isFooter(r: Rect) = centerY(r) > 0.75f
        fun looksLikeDateOrTime(s: String): Boolean {
            val t = s.lowercase(Locale.US)
            val dateLike = Regex("""\b(\d{1,2}[/-]\d{1,2}([/-]\d{2,4})?)\b""")
            val timeLike = Regex("""\b(\d{1,2}:\d{2}(:\d{2})?\s?(am|pm)?)\b""")
            val monthNames = listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
            return dateLike.containsMatchIn(t) || timeLike.containsMatchIn(t) || monthNames.any { t.contains(it) }
        }

        val phrases = mutableListOf<LTok>()
        var i = 0
        val n = kotlin.math.min(merged.size, tags.size)
        while (i < n) {
            val tag = tags[i]
            if (tag.startsWith("B-")) {
                val baseTag = tag.substring(2)
                val startBox = Rect(merged[i].box)
                val sb = java.lang.StringBuilder(merged[i].text)
                var j = i + 1
                while (j < n && tags[j] == "I-$baseTag") {
                    sb.append(" ").append(merged[j].text)
                    startBox.union(merged[j].box)
                    j++
                }
                phrases.add(LTok(sb.toString().trim(), startBox, baseTag))
                i = j
            } else i++
        }
        
        val storeCand = phrases.filter { it.tag == "STORE" && isHeader(it.box) }.maxByOrNull { it.text.length }
        val storeName = storeCand?.text ?: fallbackStoreFromHeader(merged, pageHeight)

        var lines = groupTokensIntoLines(merged, pageHeight).map { it.toList() }

        fun amountsOnLine(line: List<UiTok>): List<Double> {
            val res = line.flatMap { parseAmountsInText(it.text) }.filter { it >= 0.0 && it < 100_000.0 }.toMutableList()
            if (res.isEmpty() && line.isNotEmpty()) {
                val lastTokRaw = line.last().text.trim()
                if (lastTokRaw.matches(Regex("""^[$€£]?\d{1,4}$"""))) {
                    parseAmountString(lastTokRaw)?.let { res.add(it) }
                }
            }
            return res
        }

        // --- MERGE VERTICAL MULTI-LINE ITEMS (e.g., More Retail Indian receipts and Grotto modifiers) ---
        val pageWidthApprox = merged.maxOfOrNull { it.box.right }?.toFloat() ?: 1000f
        val indentThreshold = pageWidthApprox * 0.015f

        val consolidatedLines = mutableListOf<List<UiTok>>()
        var lineIdx = 0
        while (lineIdx < lines.size) {
            var currentLine = lines[lineIdx]
            
            while (lineIdx + 1 < lines.size) {
                val nextLine = lines[lineIdx + 1]
                if (isHeader(currentLine.first().box) || isFooter(currentLine.first().box)) break
                if (isHeader(nextLine.first().box) || isFooter(nextLine.first().box)) break
                
                val currentY = currentLine.map { ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat() }.average().toFloat()
                val nextY = nextLine.map { ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat() }.average().toFloat()
                if (nextY - currentY > 0.05f) break // Too far apart vertically
                
                val currentLeft = currentLine.first().box.left.toFloat()
                val nextLeft = nextLine.first().box.left.toFloat()
                
                val currentAmounts = amountsOnLine(currentLine)
                val nextAmounts = amountsOnLine(nextLine)

                val isMoreRetail = currentAmounts.isEmpty() && nextAmounts.isNotEmpty() && nextLeft >= currentLeft - indentThreshold
                val isGrotto = currentAmounts.isNotEmpty() && nextAmounts.isEmpty() && nextLeft >= currentLeft + indentThreshold
                val isMultiLineModifier = currentAmounts.isEmpty() && nextAmounts.isEmpty() && nextLeft >= currentLeft - indentThreshold
                
                if (isMoreRetail || isGrotto || isMultiLineModifier) {
                    currentLine = (currentLine + nextLine).sortedBy { it.box.left }
                    lineIdx++
                } else {
                    break
                }
            }
            consolidatedLines.add(currentLine)
            lineIdx++
        }
        lines = consolidatedLines

        fun findLineForBox(box: Rect): List<UiTok>? {
            val cy = ((box.top + box.bottom) * 0.5f) / pageHeight.toFloat()
            return lines.minByOrNull { line ->
                val ly = line.map { ((it.box.top + it.box.bottom) * 0.5f) / pageHeight.toFloat() }.average().toFloat()
                kotlin.math.abs(ly - cy)
            }
        }

        val items = mutableListOf<ReceiptItem>()
        val names = phrases.filter { it.tag == "ITEM_NAME" && !looksLikeDateOrTime(it.text) }
        val pricePhrases = phrases.filter { it.tag == "ITEM_PRICE" }

        for (name in names) {
            val cy = ((name.box.top + name.box.bottom) * 0.5f) / pageHeight.toFloat()
            val sameLineKie = pricePhrases.filter { p ->
                val py = ((p.box.top + p.box.bottom) * 0.5f) / pageHeight.toFloat()
                kotlin.math.abs(py - cy) < 0.03f && p.box.left >= name.box.left
            }.sortedBy { it.box.left - name.box.left }
            val priceFromKie: Double? = sameLineKie.firstNotNullOfOrNull { parseAmountString(it.text) }

            val line: List<UiTok>? = findLineForBox(name.box)
            val qty: Int? = line?.let { extractQuantityFromLine(it) }
            val priceFromRight: Double? = line?.let { rightwardPriceForItem(it, UiTok(name.text, name.box)) }
            val priceFromRightmost: Double? = line?.let { rightmostPriceOnLine(it) }

            val priceFromEmbedded: Double? = parseAmountsInText(name.text).lastOrNull()

            val selected: Double? = listOfNotNull(priceFromKie, priceFromRight, priceFromRightmost, priceFromEmbedded)
                .firstOrNull()
                ?.takeIf { it >= 0.0 && it < 100_000.0 }

            var finalPrice: Double? = selected
            if (line != null && qty != null && qty >= 2) {
                val q: Int = qty
                val amts: List<Double> = amountsOnLine(line).sorted()
                if (amts.isNotEmpty()) {
                    val maxAmt: Double = amts.last()
                    val maybeUnit: Double = amts.first()
                    val unitTimesQty: Double = maybeUnit * q.toDouble()
                    val tol: Double = kotlin.math.max(0.02, 0.01 * unitTimesQty)
                    if (kotlin.math.abs(maxAmt - unitTimesQty) <= tol) {
                        finalPrice = maxAmt
                    } else if (selected != null) {
                        finalPrice = selected * q.toDouble()
                    }
                } else if (selected != null) {
                    finalPrice = selected * q.toDouble()
                }
            }

            finalPrice = finalPrice?.takeIf { it >= 0.0 && it < 100_000.0 }
            if (finalPrice != null) {
                val cleanedName = name.text.replace(
                    Regex("""\$?\s*-?\d{1,3}(,\d{3})*(\.\d{1,2})|\$?\s*-?\d+(?:\.\d{1,2})|\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})"""),
                    ""
                ).replace(Regex("""^\s*[A-Za-z]\s+"""), "")
                 .replace(Regex("""\s+[A-Za-z]\s*$"""), "")
                 .trim()
                items.add(
                    ReceiptItem(
                        name = if (cleanedName.isNotEmpty()) cleanedName else name.text,
                        price = finalPrice,
                        quantity = qty?.toDouble(),
                        unit = null
                    )
                )
            }
        }

        val priceWordKeysTotal = listOf(" TOTAL ", "AMOUNT DUE", "BALANCE DUE", "GRAND TOTAL", "IMPORTE TOTAL", "TOTAL A PAGAR", "SUMA TOTAL")
        val priceWordKeysSub = listOf("SUBTOTAL", "SUB TOTAL", "SUB-TOTAL", "SUBTOTAL:", "SUBTOTALES")
        val priceWordKeysTax = listOf("TAX", "VAT", "IVA", "TVA", "IGV", "GST", "BTW", "MWST", "TPS", "TVQ", "IMPUESTO", "IMP.")

        // --- Strong Heuristic Item Fallback ---
        val heuristicItems = mutableListOf<ReceiptItem>()
        val priceRegex = Regex("""\$?\s*-?\d{1,3}(,\d{3})*(\.\d{1,2})|\$?\s*-?\d+(?:\.\d{1,2})|\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})""")
        
        for (line in lines) {
            if (line.isEmpty() || isHeader(line.first().box) || isFooter(line.first().box)) continue
            
            val rawLine = line.joinToString(" ") { it.text }
            val fixedLine = rawLine.replace('\u00A0', ' ').replace(Regex("""([.,])\s+(\d{1,2})(?!\d)"""), "$1$2")
            val up = fixedLine.uppercase(Locale.US)
            
            // Skip common non-item lines
            if (priceWordKeysTotal.any { up.contains(it.trim()) } || 
                priceWordKeysTax.any { up.contains(it) } || 
                priceWordKeysSub.any { up.contains(it) } ||
                up.contains("CHANGE") || up.contains("CAMBIO") || up.contains("CASH")) continue
                
            val amounts = amountsOnLine(line)
            if (amounts.isNotEmpty()) {
                val qty = extractQuantityFromLine(line)
                val price = amounts.last()
                
                // Clean the name
                var nameCand = fixedLine.replace(priceRegex, "")
                if (price == parseAmountString(line.last().text)) {
                    nameCand = nameCand.removeSuffix(line.last().text)
                }
                nameCand = nameCand
                    .replace(Regex("""^\s*\d+\s+"""), "") // strip leading quantity
                    .replace(Regex("""^\s*x\s+"""), "")   // strip leading multiplier
                    .replace(Regex("""^\s*[A-Za-z]\s+"""), "") // strip leading isolated char (like E)
                    .replace(Regex("""\s+[A-Za-z]\s*$"""), "") // strip trailing isolated char
                    .trim()
                
                // If the remainder looks like an actual product string
                if (nameCand.length >= 3 && nameCand.any { it.isLetter() } && !looksLikeDateOrTime(nameCand)) {
                    heuristicItems.add(ReceiptItem(name = nameCand, quantity = qty?.toDouble(), price = price))
                }
            }
        }
        
        // Override KIE if the raw physical layout density yields more items
        if (heuristicItems.size > items.size) {
            items.clear()
            items.addAll(heuristicItems)
            Log.d("KIE_DEBUG", "Fallback heuristic yielded ${heuristicItems.size} items, overriding KIE")
        }

        fun findLineByKeys(keys: List<String>): List<UiTok>? {
            return lines.firstOrNull { line ->
                val up = (" " + line.joinToString(" ") { it.text.uppercase(Locale.US) } + " ")
                keys.any { k -> up.contains(k) }
            }
        }

        val totalFromKie = phrases.firstOrNull { it.tag == "TOTAL" }?.text?.let { parseAmountString(it) }
        var subtotal = phrases.firstOrNull { it.tag == "SUBTOTAL" }?.text?.let { parseAmountString(it) }
        if (subtotal == null) subtotal = findLineByKeys(priceWordKeysSub)?.let { rightmostPriceOnLine(it) }

        var tax = phrases.firstOrNull { it.tag == "TAX" }?.text?.let { parseAmountString(it) }
        if (tax == null) tax = findLineByKeys(priceWordKeysTax)?.let { rightmostPriceOnLine(it) }

        fun lexicalTotalFromMerged(): Double? = findLineByKeys(priceWordKeysTotal)?.let { rightmostPriceOnLine(it) }
        fun footerMaxPrice(): Double? = merged.filter { isFooter(it.box) }.flatMap { parseAmountsInText(it.text) }.maxOrNull()
        fun subtotalPlusTax(st: Double?, tx: Double?): Double? = if (st != null && tx != null) "%.2f".format(Locale.US, st + tx).toDouble() else null

        val total = totalFromKie ?: lexicalTotalFromMerged() ?: subtotalPlusTax(subtotal, tax) ?: footerMaxPrice()

        val dateText = phrases.firstOrNull { it.tag == "DATE" }?.text
        val timeText = phrases.firstOrNull { it.tag == "TIME" }?.text
        val date: LocalDate? = try { dateText?.let { parseLocalDateFromText(it) } } catch (_: Exception) { null }
        val time: LocalTime? = try { timeText?.let { parseLocalTimeFromText(it) } } catch (_: Exception) { null }

        val headerPhrases = phrases.filter { isHeader(it.box) }
        val address = headerPhrases.firstOrNull { it.tag == "ADDRESS" }?.text
        val phone = headerPhrases.firstOrNull { it.tag == "PHONE" }?.text

        val kiePaymentText = phrases.firstOrNull { it.tag == "PAYMENT" }?.text
        val payment = kiePaymentText?.let { paymentFromKieText(it) } ?: extractPaymentMethod(merged, pageHeight)

        return ReceiptData(
            storeName = storeName,
            storeAddress = address,
            storePhone = phone,
            date = date,
            time = time,
            items = items,
            subtotal = subtotal,
            tax = tax,
            total = total,
            paymentMethod = payment,
            transactionId = null,
            cashier = null,
            rawText = merged.joinToString("\n") { it.text }
        )
    }
}
