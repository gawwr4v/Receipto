package com.example.receipto.parser

/**
 * Clean and normalize OCR text for better parsing
 */
object TextCleaner {

    /**
     * Clean raw OCR text
     */
    fun clean(rawText: String): String {
        return rawText
            .fixCommonOcrErrors()
            .normalizeWhitespace()
            .removeExtraNewlines()
            .trim()
    }

    private fun String.fixCommonOcrErrors(): String {
        return this
            // Number/Letter confusion
            .replace(Regex("""\bO(?=\d)"""), "0")
            .replace(Regex("""(?<=\d)O\b"""), "0")
            .replace(Regex("""\bl(?=\d)"""), "1")
            .replace(Regex("""(?<=\d)l\b"""), "1")
            .replace(Regex("""\bS(?=\d)"""), "5")
            .replace(Regex("""\bB(?=\d)"""), "8")

            // Common word errors
            .replace("Sa les", "Sales", ignoreCase = true)
            .replace("T0TAL", "TOTAL", ignoreCase = true)
            .replace("T0tal", "Total", ignoreCase = true)
            .replace("TOTA L", "TOTAL", ignoreCase = true)
            .replace("CA5H", "CASH", ignoreCase = true)
            .replace("CRED1T", "CREDIT", ignoreCase = true)

            // Currency symbol confusion
            .replace(" S ", " $ ", ignoreCase = false)  // S misread as $
            .replace("$$ ", "$ ")  // Double $

            // Price formatting
            .replace("LI-", "1.")
            .replace("l-", "1.")
            .replace("I-", "1.")
            .replace(" - ", ".")  // Sometimes - is misread period

        // Decimal/comma normalization happens later in pipeline
    }

    /**
     * Normalize whitespace
     */
    private fun String.normalizeWhitespace(): String {
        return this
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\t", " ")
            .replace(Regex(""" +"""), " ")  // Multiple spaces → single space
    }

    /**
     * Remove excessive newlines
     */
    private fun String.removeExtraNewlines(): String {
        return this.replace(Regex("""\n{3,}"""), "\n\n")
    }

    /**
     * Split text into clean lines
     */
    fun getCleanLines(text: String): List<String> {
        return clean(text)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Extract numbers from text (for prices, dates, etc.)
     */
    fun extractNumbers(text: String): List<Double> {
        val numberPattern = Regex("""\d+[.,]\d+|\d+""")
        return numberPattern.findAll(text)
            .mapNotNull {
                it.value.replace(",", ".").toDoubleOrNull()
            }
            .toList()
    }

    /**
     * Fuzzy match for store names (handles typos)
     */
    fun fuzzyMatch(input: String, target: String, threshold: Double = 0.7): Boolean {
        val inputClean = input.lowercase().replace(Regex("""[^a-z0-9]"""), "")
        val targetClean = target.lowercase().replace(Regex("""[^a-z0-9]"""), "")

        if (inputClean == targetClean) return true

        val similarity = calculateSimilarity(inputClean, targetClean)
        return similarity >= threshold
    }

    /**
     * Calculate Levenshtein distance-based similarity
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1

        if (longer.isEmpty()) return 1.0

        val editDistance = levenshteinDistance(longer, shorter)
        return (longer.length - editDistance).toDouble() / longer.length
    }

    /**
     * Levenshtein distance (edit distance)
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1) { it }

        for (i in 1..s1.length) {
            var lastValue = i
            for (j in 1..s2.length) {
                val newValue = if (s1[i - 1] == s2[j - 1]) {
                    costs[j - 1]
                } else {
                    minOf(costs[j - 1], lastValue, costs[j]) + 1
                }
                costs[j - 1] = lastValue
                lastValue = newValue
            }
            costs[s2.length] = lastValue
        }

        return costs[s2.length]
    }

    /**
     * Normalize currency symbols and formats
     */
    fun normalizeCurrency(text: String): String {
        return text
            // Standardize currency symbols to $
            .replace("€", "$")
            .replace("£", "$")
            .replace("¥", "$")
            .replace("₹", "$")

            // Fix spacing around currency
            .replace(Regex("""\$\s*(\d)"""), "$$1")  // Remove space after $
            .replace(Regex("""(\d)\s*\$"""), "$1$")  // Remove space before $

            // Handle negative amounts
            .replace("($", "-$")
            .replace(")$", "")
    }



    /**
     * Clean price string to standardized format
     */
    fun cleanPriceString(priceStr: String): String {
        return priceStr
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
            .replace("¥", "")
            .replace("₹", "")
            .replace(" ", "")
            .trim()
    }


    /**
     * Enhanced number extraction with currency awareness
     */

    fun extractPrices(text: String): List<Double> {
        val prices = mutableListOf<Double>()
        val pricePattern = Regex("""$?\s*(\d{1,}[.,]\d{2})\b""")

        pricePattern.findAll(text).forEach { match ->
            val priceStr = match.groupValues[1].replace(",", ".")

            priceStr.toDoubleOrNull()?.let {
                if (it > 0.0 && it < 1_000_000.0) {
                    prices.add(it)
                }
            }
        }

        return prices

    }
}