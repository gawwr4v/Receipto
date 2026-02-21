package com.example.receipto.ocr

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class DetectedRegion(
    val boundingBox: Rect,
    val relativeY: Float  // Position relative to image height (0.0 to 1.0)
)

object LayoutDetector {

    /**
     * Detect text regions in the image
     * Returns list of bounding boxes sorted top-to-bottom
     */
    fun detectRegions(bitmap: Bitmap): List<DetectedRegion> {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

        // Binarize
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,  // Inverted for morphology
            11,
            2.0
        )

        // Morphological operations to connect text
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(20.0, 5.0)  // Horizontal kernel to connect words
        )
        val dilated = Mat()
        Imgproc.dilate(binary, dilated, kernel, Point(-1.0, -1.0), 2)

        // Find contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            dilated,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Extract bounding boxes and filter small noise
        val imageHeight = bitmap.height.toFloat()
        val regions = contours
            .map { Imgproc.boundingRect(it) }
            .filter { it.width > 50 && it.height > 10 }  // Filter noise
            .map { rect ->
                DetectedRegion(
                    boundingBox = rect,
                    relativeY = rect.y / imageHeight
                )
            }
            .sortedBy { it.boundingBox.y }  // Sort top to bottom

        // Cleanup
        mat.release()
        gray.release()
        binary.release()
        kernel.release()
        dilated.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return regions
    }
}
