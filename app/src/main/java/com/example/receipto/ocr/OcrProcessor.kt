package com.example.receipto.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.receipto.util.ImageUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Processes images to extract text using ML Kit's on-device OCR
 * Now with OpenCV preprocessing and layout detection!
 * This runs completely offline - no internet needed!
 */
class OcrProcessor(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extract text from an image URI
     * Returns OcrResult with extracted text, confidence, and detected regions
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        return try {
            // Load bitmap from URI with orientation correction
            val bitmap = ImageUtils.loadBitmapFromUri(context, imageUri)
                ?: return OcrResult.Error("Failed to load image")

            // Resize for better performance (ML Kit works well with ~1920px images)
            val resizedBitmap = ImageUtils.resizeBitmap(bitmap, maxWidth = 1920, maxHeight = 1920)

            // Process with ML Kit
            processImage(resizedBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            OcrResult.Error("OCR failed: ${e.message}")
        }
    }

    /**
     * Extract text from a Bitmap with OpenCV preprocessing
     */
    suspend fun processImage(bitmap: Bitmap): OcrResult {
        return try {
            Log.d("OcrProcessor", "Starting OCR processing with OpenCV pipeline")

            // OCR on original bitmap
            val originalInputImage = InputImage.fromBitmap(bitmap, 0)
            val originalVisionText = textRecognizer.process(originalInputImage).await()
            Log.d("OcrProcessor", "ORIGINAL OCR detected ${originalVisionText.text.length} characters")

            // Preprocess with OpenCV
            val preprocessedBitmap = try {
                ImagePreprocessor.preprocess(bitmap)
            } catch (e: Exception) {
                Log.e("OcrProcessor", "OpenCV preprocessing failed", e)
                bitmap
            }

            // OCR on preprocessed bitmap
            val preprocessedInputImage = InputImage.fromBitmap(preprocessedBitmap, 0)
            val preprocessedVisionText = textRecognizer.process(preprocessedInputImage).await()
            Log.d("OcrProcessor", "PREPROCESSED OCR detected ${preprocessedVisionText.text.length} characters")

            // Detect layout regions on the preprocessed image (more stable)
            val detectedRegions = try {
                LayoutDetector.detectRegions(preprocessedBitmap)
            } catch (e: Exception) {
                Log.e("OcrProcessor", "Layout detection failed", e)
                emptyList()
            }

            val classifiedRegions = try {
                RegionClassifier.classify(detectedRegions)
            } catch (e: Exception) {
                Log.e("OcrProcessor", "Region classification failed", e)
                emptyList()
            }

            Log.d("OcrProcessor", "Detected ${classifiedRegions.size} regions")

            // Choose the better OCR result (more characters)
            val chosenVisionText = if (originalVisionText.text.length > preprocessedVisionText.text.length) {
                Log.d("OcrProcessor", "Using ORIGINAL (better)")
                originalVisionText
            } else {
                Log.d("OcrProcessor", "Using PREPROCESSED (better)")
                preprocessedVisionText
            }

            if (chosenVisionText.text.isEmpty()) {
                OcrResult.Error("No text detected in image")
            } else {
                // Use the incoming bitmap dimensions as page dims (preprocess preserves size)
                parseVisionTextWithRegions(
                    visionText = chosenVisionText,
                    regions = classifiedRegions,
                    pageWidth = bitmap.width,
                    pageHeight = bitmap.height
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            OcrResult.Error("OCR failed: ${e.message}")
        }
    }

    /**
     * Parse ML Kit's Text result into our structured format with region information
     * Also includes word-level tokens (with bounding boxes) and page dimensions.
     */
    private fun parseVisionTextWithRegions(
        visionText: Text,
        regions: List<ClassifiedRegion>,
        pageWidth: Int,
        pageHeight: Int
    ): OcrResult {
        val fullText = visionText.text
        val lines = mutableListOf<TextLine>()
        val words = mutableListOf<String>()
        val tokens = mutableListOf<OcrToken>() // word-level tokens + bounding boxes

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text
                val confidence = line.confidence ?: 0f

                lines.add(
                    TextLine(
                        text = lineText,
                        confidence = confidence,
                        boundingBox = line.boundingBox
                    )
                )

                for (element in line.elements) {
                    words.add(element.text)
                    val r = element.boundingBox
                    if (r != null) {
                        tokens.add(OcrToken(text = element.text, boundingBox = r))
                    }
                }
            }
        }

        return OcrResult.Success(
            fullText = fullText,
            lines = lines,
            words = words,
            blockCount = visionText.textBlocks.size,
            detectedRegions = regions,
            tokens = tokens,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
    }

    /**
     * Legacy parse (no regions, no tokens)
     */
    private fun parseVisionText(visionText: Text): OcrResult {
        // Page dims unknown here; pass zeros (callers should prefer the regions variant)
        return parseVisionTextWithRegions(visionText, emptyList(), 0, 0)
    }

    /**
     * Clean up resources
     */
    fun close() {
        textRecognizer.close()
    }
}

/**
 * Result of OCR processing
 */
sealed class OcrResult {
    data class Success(
        val fullText: String,
        val lines: List<TextLine>,
        val words: List<String>,
        val blockCount: Int,
        val detectedRegions: List<ClassifiedRegion> = emptyList(),
        // NEW: word-level tokens for KIE and general-purpose spatial post-processing
        val tokens: List<OcrToken> = emptyList(),
        // NEW: original page dimensions for bbox normalization / KIE encoding
        val pageWidth: Int = 0,
        val pageHeight: Int = 0
    ) : OcrResult() {
        val lineCount: Int get() = lines.size
        val wordCount: Int get() = words.size
        val avgConfidence: Float get() =
            if (lines.isEmpty()) 0f else lines.map { it.confidence }.average().toFloat()

        // Optional helper (depends on ClassifiedRegion carrying text; safe to ignore if blank)
        fun getTextInRegion(regionType: RegionType): String {
            return detectedRegions
                .filter { it.type == regionType }
                .joinToString("\n") { it.text }
        }

        fun hasRegions(): Boolean = detectedRegions.isNotEmpty()
    }

    data class Error(val message: String) : OcrResult()
}

/**
 * Represents a single line of detected text
 */
data class TextLine(
    val text: String,
    val confidence: Float,
    val boundingBox: android.graphics.Rect?
)

/**
 * Represents a single word/token with its bounding box
 */
data class OcrToken(
    val text: String,
    val boundingBox: android.graphics.Rect
)