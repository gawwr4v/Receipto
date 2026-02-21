package com.example.receipto.ocr

enum class RegionType {
    HEADER,
    ITEMS,
    TOTALS,
    FOOTER,
    UNKNOWN
}

data class ClassifiedRegion(
    val region: DetectedRegion,
    val type: RegionType,
    val text: String = ""
)

object RegionClassifier {

    /**
     * Classify detected regions based on position and characteristics
     */
    fun classify(regions: List<DetectedRegion>): List<ClassifiedRegion> {
        return regions.map { region ->
            val type = classifyByPosition(region.relativeY, region.boundingBox)
            ClassifiedRegion(region, type)
        }
    }

    /**
     * Position-based classification rules
     */
    private fun classifyByPosition(relativeY: Float, box: org.opencv.core.Rect): RegionType {
        return when {
            // Top 20% = Header (store name, address, date)
            relativeY < 0.2f -> RegionType.HEADER

            // Bottom 15% = Footer (bar code, thank you message)
                relativeY > 0.85f -> RegionType.FOOTER

            // Bottom 20-30% = Totals (subtotal, tax, total)
            relativeY > 0.70f -> RegionType.TOTALS

            // Middle 20-70% = Items list
            relativeY in 0.2f..0.70f -> {
                // Additional heuristic: Items are usually medium-sized repeated blocks
                if (box.height in 15..50 && box.width > 100) {
                    RegionType.ITEMS
                } else {
                    RegionType.UNKNOWN
                }
            }

            else -> RegionType.UNKNOWN
        }
    }

}