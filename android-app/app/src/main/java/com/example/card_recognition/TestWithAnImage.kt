package com.example.card_recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Test class để debug và so sánh chất lượng recognition
 * Chạy với ảnh test.jpg từ assets và in ra thông tin chi tiết từng bước
 */
class TestWithAnImage(private val context: Context) {

    companion object {
        private const val TAG = "TestWithAnImage"
        private const val TEST_IMAGE_NAME = "test.jpg"
    }

    private val detector = Detector()
    private val recognizer = Recognizer()
    private var embeddingData: EmbeddingData? = null

    /**
     * Khởi tạo detector và recognizer
     */
    fun initialize() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "INITIALIZING TEST")
        Log.i(TAG, "========================================")

        try {
            // Initialize detector
            Log.i(TAG, "1. Initializing Detector...")
            detector.initialize(context)
            Log.i(TAG, "   ✓ Detector initialized successfully")

            // Initialize recognizer
            Log.i(TAG, "2. Initializing Recognizer...")
            recognizer.initialize(context, useNNAPI = false) // Use CPU for consistency
            Log.i(TAG, "   ✓ Recognizer initialized successfully")

            // Load embedding data
            Log.i(TAG, "3. Loading Embedding Data...")
            embeddingData = EmbeddingData.loadFromAssets(context, "data.json")
            if (embeddingData != null) {
                Log.i(TAG, "   ✓ Embedding data loaded successfully")
                Log.i(TAG, "   - Categories: ${embeddingData!!.getCategoryCount()}")
                Log.i(TAG, "   - Total embeddings: ${embeddingData!!.getTotalEmbeddingCount()}")
            } else {
                Log.e(TAG, "   ✗ Failed to load embedding data")
            }

            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "Error during initialization", e)
        }
    }

    /**
     * Chạy test với ảnh test.jpg từ assets
     */
    fun runTest() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "RUNNING TEST WITH: $TEST_IMAGE_NAME")
        Log.i(TAG, "========================================")

        if (embeddingData == null) {
            Log.e(TAG, "Embedding data not loaded. Call initialize() first!")
            return
        }

        try {
            // Step 1: Load image from assets
            Log.i(TAG, "\n--- STEP 1: LOAD IMAGE ---")
            val bitmap = loadImageFromAssets(TEST_IMAGE_NAME)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load image: $TEST_IMAGE_NAME")
                return
            }
            Log.i(TAG, "Image loaded successfully")
            Log.i(TAG, "  - Width: ${bitmap.width}")
            Log.i(TAG, "  - Height: ${bitmap.height}")
            Log.i(TAG, "  - Config: ${bitmap.config}")

            // Step 2: Run detection
            Log.i(TAG, "\n--- STEP 2: OBJECT DETECTION ---")
            val detectionStartTime = System.currentTimeMillis()
            val detections = detector.detect(bitmap)
            val detectionDuration = System.currentTimeMillis() - detectionStartTime

            Log.i(TAG, "Detection completed in ${detectionDuration}ms")

            if (detections == null) {
                Log.e(TAG, "Detection returned null. Test stopped.")
                return
            }

            Log.i(TAG, "Found ${detections.size} detection(s)")

            detections.forEachIndexed { index, detection ->
                Log.i(TAG, "  Detection #$index:")
                Log.i(TAG, "    - Bbox: [${detection.bbox[0]}, ${detection.bbox[1]}, ${detection.bbox[2]}, ${detection.bbox[3]}]")
                Log.i(TAG, "    - Confidence: ${detection.confidence}")
                Log.i(TAG, "    - Class: ${detection.classId}")
            }

            if (detections.isEmpty()) {
                Log.w(TAG, "No detections found. Test stopped.")
                return
            }

            // Step 3: Process each detection
            Log.i(TAG, "\n--- STEP 3: RECOGNITION ---")

            detections.forEachIndexed { index, detection ->
                Log.i(TAG, "\n>>> Processing Detection #$index <<<")

                // 3.1: Crop bbox (NO padding - same as Python)
                Log.i(TAG, "  3.1. Cropping bbox...")
                val imgWidth = bitmap.width
                val imgHeight = bitmap.height

                val x1 = detection.bbox[0].toInt().coerceAtLeast(0)
                val y1 = detection.bbox[1].toInt().coerceAtLeast(0)
                val x2 = detection.bbox[2].toInt().coerceAtMost(imgWidth)
                val y2 = detection.bbox[3].toInt().coerceAtMost(imgHeight)

                val width = x2 - x1
                val height = y2 - y1

                Log.i(TAG, "    - Crop coordinates: ($x1, $y1, $x2, $y2)")
                Log.i(TAG, "    - Crop size: ${width}x${height}")

                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "    ✗ Invalid crop size, skipping...")
                    return@forEachIndexed
                }

                val croppedBitmap = Bitmap.createBitmap(bitmap, x1, y1, width, height)
                Log.i(TAG, "    ✓ Cropped bitmap: ${croppedBitmap.width}x${croppedBitmap.height}")

                // 3.2: Extract embedding
                Log.i(TAG, "  3.2. Extracting embedding...")
                val embeddingStartTime = System.currentTimeMillis()
                val embedding = recognizer.extractEmbedding(croppedBitmap)
                val embeddingDuration = System.currentTimeMillis() - embeddingStartTime

                croppedBitmap.recycle()

                if (embedding == null) {
                    Log.e(TAG, "    ✗ Failed to extract embedding")
                    return@forEachIndexed
                }

                Log.i(TAG, "    ✓ Embedding extracted in ${embeddingDuration}ms")
                Log.i(TAG, "    - Embedding size: ${embedding.size}")
                Log.i(TAG, "    - First 10 values: ${embedding.take(10).joinToString(", ") { "%.4f".format(it) }}")
                Log.i(TAG, "    - Last 10 values: ${embedding.takeLast(10).joinToString(", ") { "%.4f".format(it) }}")

                // Calculate embedding statistics
                val mean = embedding.average().toFloat()
                val max = embedding.maxOrNull() ?: 0f
                val min = embedding.minOrNull() ?: 0f
                val std = kotlin.math.sqrt(embedding.map { (it - mean) * (it - mean) }.average()).toFloat()

                Log.i(TAG, "    - Statistics:")
                Log.i(TAG, "      * Mean: %.4f".format(mean))
                Log.i(TAG, "      * Std: %.4f".format(std))
                Log.i(TAG, "      * Min: %.4f".format(min))
                Log.i(TAG, "      * Max: %.4f".format(max))

                // 3.3: Find best match
                Log.i(TAG, "  3.3. Finding best match...")
                val matchStartTime = System.currentTimeMillis()
                val matchResult = embeddingData!!.findBestMatch(
                    queryEmbedding = embedding,
                    threshold = 0.75f
                )
                val matchDuration = System.currentTimeMillis() - matchStartTime

                Log.i(TAG, "    ✓ Matching completed in ${matchDuration}ms")
                Log.i(TAG, "    - Best category: ${matchResult.category ?: "Unknown"}")
                Log.i(TAG, "    - Similarity: %.4f".format(matchResult.similarity))
                Log.i(TAG, "    - Is match: ${matchResult.isMatch}")
                Log.i(TAG, "    - Detection confidence: %.4f".format(detection.confidence))

                // 3.4: Final result
                Log.i(TAG, "  3.4. Final result:")
                if (matchResult.isMatch) {
                    Log.i(TAG, "    ✓✓✓ RECOGNIZED: ${matchResult.category}")
                    Log.i(TAG, "        Detection confidence: %.2f%%".format(detection.confidence * 100))
                    Log.i(TAG, "        Recognition confidence: %.2f%%".format(matchResult.similarity * 100))
                } else {
                    Log.i(TAG, "    ✗✗✗ UNKNOWN (similarity too low)")
                    Log.i(TAG, "        Best match: ${matchResult.category}")
                    Log.i(TAG, "        Similarity: %.2f%% (threshold: 75%%)".format(matchResult.similarity * 100))
                }
            }

            Log.i(TAG, "\n========================================")
            Log.i(TAG, "TEST COMPLETED")
            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "Error during test", e)
            e.printStackTrace()
        }
    }

    /**
     * Load image từ assets
     */
    private fun loadImageFromAssets(fileName: String): Bitmap? {
        return try {
            context.assets.open(fileName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image from assets: $fileName", e)
            null
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up resources...")
        detector.close()
        recognizer.close()
        Log.i(TAG, "Cleanup completed")
    }
}

/**
 * Extension function để format số
 */
private fun String.Companion.format(format: String, vararg args: Any): String {
    return java.lang.String.format(format, *args)
}