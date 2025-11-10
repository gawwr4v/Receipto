package com.example.receipto.parser

object ParsingRules {

    // ========== DATE & TIME PATTERNS ==========

    val datePatterns = listOf(
        Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})\b"""),
        Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2})\b"""),
        Regex("""\b(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})\b"""),
        Regex("""\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\w*\s+(\d{1,2}),?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    )

    val timePatterns = listOf(
        Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM|am|pm)?\b"""),
        Regex("""\b(\d{4})\b""")
    )

    // ========== PRICE PATTERNS ==========

    val pricePatterns = listOf(
        Regex("""\$\s*(\d{1,})[.,](\d{2})\b"""),
        Regex("""\b(\d{1,})[.,](\d{2})\s*[$€£¥₹]\b"""),
        Regex("""\b(\d{1,})[.,](\d{2})\b"""),
        Regex("""\b(\d{1,3})[.,](\d{3})[.,](\d{2})\b"""),
        Regex("""[-\(]\$?\s*(\d{1,})[.,](\d{2})\)?""")
    )

    // ========== KEYWORDS ==========

    val storeNameIndicators = listOf(
        "store", "market", "shop", "mart", "supermarket",
        "grocery", "pharmacy", "restaurant", "cafe", "coffee"
    )

    val addressIndicators = listOf(
        "street", "st", "avenue", "ave", "road", "rd", "drive", "dr",
        "blvd", "boulevard", "lane", "ln", "plaza", "suite", "unit"
    )

    val totalKeywords = listOf(
        "total", "grand total", "amount due", "balance due",
        "amount", "net total", "final total", "gtotal", "g.total"
    )

    val subtotalKeywords = listOf(
        "subtotal", "sub-total", "sub total", "sub",
        "merchandise total", "item total"
    )

    val taxKeywords = listOf(
        "tax", "sales tax", "gst", "vat", "hst",
        "state tax", "local tax", "sales", "levy"
    )

    val paymentKeywords = listOf(
        "cash", "credit", "debit", "card", "visa", "mastercard",
        "amex", "discover", "payment", "paid", "tender"
    )

    val excludeFromStoreName = listOf(
        "receipt", "invoice", "bill", "ticket", "order",
        "thank you", "thanks", "welcome", "goodbye"
    )

    // ========== HELPER FUNCTIONS ==========

    fun isLikelyItemLine(line: String): Boolean {
        val cleanLine = line.trim()

        // More lenient minimum length
        if (cleanLine.length < 2) return false

        val hasText = cleanLine.any { it.isLetter() }
        val endsWithNumber = pricePatterns.any { it.find(cleanLine) != null }

        val hasExcludedKeyword = totalKeywords.any {
            cleanLine.contains(it, ignoreCase = true)
        } || taxKeywords.any {
            cleanLine.contains(it, ignoreCase = true)
        } || subtotalKeywords.any {
            cleanLine.contains(it, ignoreCase = true)
        }

        // More lenient: just needs text OR price (not necessarily both)
        return (hasText || endsWithNumber) && !hasExcludedKeyword
    }

    fun extractAllPricesFromLine(line: String): List<Double> {
        val prices = mutableListOf<Double>()

        val cleanLine = line
            .replace("$", " $ ")
            .replace("€", " € ")
            .replace("£", " £ ")
            .trim()

        for (pattern in pricePatterns) {
            pattern.findAll(cleanLine).forEach { match ->
                val priceStr = match.value
                    .replace("$", "")
                    .replace("€", "")
                    .replace("£", "")
                    .replace("¥", "")
                    .replace("₹", "")
                    .replace(" ", "")
                    .replace(",", ".")
                    .replace("(", "")
                    .replace(")", "")
                    .trim()

                val normalizedPrice = when {
                    priceStr.contains(",") && priceStr.contains(".") -> {
                        val lastComma = priceStr.lastIndexOf(",")
                        val lastPeriod = priceStr.lastIndexOf(".")

                        if (lastPeriod > lastComma) {
                            priceStr.replace(",", "")
                        } else {
                            priceStr.replace(".", "").replace(",", ".")
                        }
                    }
                    priceStr.contains(",") -> {
                        val parts = priceStr.split(",")
                        if (parts.size == 2 && parts[1].length == 2) {
                            priceStr.replace(",", ".")
                        } else {
                            priceStr.replace(",", "")
                        }
                    }
                    else -> priceStr
                }

                normalizedPrice.toDoubleOrNull()?.let {
                    if (it > 0.0) prices.add(it)
                }
            }
        }

        return prices.distinct()
    }

    fun extractPriceFromLine(line: String): Double? {
        val allPrices = extractAllPricesFromLine(line)
        return allPrices.lastOrNull()
    }

    fun extractLargestPriceFromLine(line: String): Double? {
        val allPrices = extractAllPricesFromLine(line)
        return allPrices.maxOrNull()
    }

    fun extractItemName(line: String): String? {
        val priceMatch = pricePatterns.mapNotNull {
            it.findAll(line).lastOrNull()
        }.lastOrNull() ?: return null

        val nameEnd = priceMatch.range.first
        val name = line.substring(0, nameEnd).trim()

        return if (name.length >= 2) name else null
    }

// ========== PHONE NUMBER PATTERNS ==========

    val phonePatterns = listOf(
        Regex("""\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}"""),
        Regex("""\+?\d{1,3}[\s.-]?\d{3}[\s.-]?\d{3}[\s.-]?\d{4}""")
    )

// ========== TRANSACTION ID PATTERNS ==========

    val transactionIdPatterns = listOf(
        Regex("""(?:trans|transaction|trans#|ref|reference)[:\s#]*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:invoice|inv)[:\s#]*(\d+)""", RegexOption.IGNORE_CASE)
    )

// ========== CASHIER PATTERNS ==========

    val cashierPatterns = listOf(
        Regex("""(?:cashier|served by|server|clerk)[:\s]+(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:your cashier was|cashier:)\s+(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE)
    )

}

