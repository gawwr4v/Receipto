package com.example.receipto.ocr

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object ImagePreprocessor {

    /**
     * Complete preprocessing pipeline
     * Returns cleaned image ready for OCR
     */
    fun preprocess(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Step 1: Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

        // Step 2: GENTLE noise reduction (smaller kernel)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)  // Gentler than 5x5

        /*
         * WHY WE STOP HERE (Offender: ML Kit):
         * A traditional OCR pipeline would do Step 3: Binarization (adaptive thresholding) 
         * and Step 4: Deskewing. 
         * 
         * We explicitly skip them. ML Kit's Text Recognition is built to handle 
         * natural images (shadows, slight angles, gradients) because it uses 
         * deep learning internally. If we binarize the image, we destroy the 
         * anti-aliased edge data and ML Kit throws a fit (massive accuracy drop). 
         * 
         * So, we just give it a slightly less noisy grayscale image and let it do its thing.
         */

        // Convert back to bitmap
        val resultBitmap = Bitmap.createBitmap(
            blurred.cols(),
            blurred.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(blurred, resultBitmap)

        // Cleanup
        mat.release()
        gray.release()
        blurred.release()

        return resultBitmap
    }

    // Deskew function kept but not used - can enable later if needed
    private fun deskew(src: Mat): Mat {
        val coords = Mat()
        Core.findNonZero(src, coords)

        if (coords.empty()) {
            coords.release()
            return src
        }

        val points = MatOfPoint2f()
        coords.convertTo(points, CvType.CV_32F)

        val rotatedRect = Imgproc.minAreaRect(points)
        var angle = rotatedRect.angle

        if (angle < -45) angle += 90

        if (Math.abs(angle) < 0.5) {
            coords.release()
            points.release()
            return src
        }

        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rotationMatrix = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(
            src,
            rotated,
            rotationMatrix,
            src.size(),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE
        )

        coords.release()
        points.release()
        rotationMatrix.release()

        return rotated
    }

}