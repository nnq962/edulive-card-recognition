package com.example.card_recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap

class Detector {

    companion object {
        private const val TAG = "Detector"
        private const val MODEL_ASSET_NAME = "yolo11n.onnx"
        
        // YOLO11 constants
        private const val INPUT_SIZE = 640
        private const val NUM_PROPOSALS = 8400
        private val CLASS_NAMES = arrayOf("card")
    }
    
    /**
     * Detection result data class
     */
    data class Detection(
        val bbox: FloatArray,      // [x1, y1, x2, y2] - absolute coordinates
        val confidence: Float,
        val classId: Int,
        val className: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Detection
            if (!bbox.contentEquals(other.bbox)) return false
            if (confidence != other.confidence) return false
            if (classId != other.classId) return false
            if (className != other.className) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bbox.contentHashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + classId
            result = 31 * result + className.hashCode()
            return result
        }
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /**
     * Khởi tạo OrtEnvironment và OrtSession để load model.
     * Tự động thử NNAPI trước, nếu thất bại sẽ chuyển sang CPU.
     */
    fun initialize(context: Context, useNNAPI: Boolean = true) {
        // Chỉ khởi tạo một lần
        if (ortSession != null) {
            Log.d(TAG, "Session đã được khởi tạo.")
            return
        }

        val startTime = System.currentTimeMillis()
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = loadModelBytesFromAssets(context, MODEL_ASSET_NAME)

            // 1. Cố gắng khởi tạo với NNAPI trước nếu được yêu cầu
            if (useNNAPI) {
                try {
                    Log.i(TAG, "Đang thử khởi tạo session với NNAPI...")
                    val sessionOptions = OrtSession.SessionOptions()
                    sessionOptions.addNnapi()
                    ortSession = ortEnvironment!!.createSession(modelBytes, sessionOptions)
                    logSuccess(startTime, "NNAPI")
                    return // Thành công với NNAPI, thoát khỏi hàm
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể khởi tạo session với NNAPI, sẽ thử lại với CPU.", e)
                    ortSession?.close() // Dọn dẹp nếu có lỗi
                    ortSession = null
                }
            }

            // 2. Nếu code chạy đến đây, nghĩa là NNAPI thất bại hoặc không được yêu cầu.
            // Thử khởi tạo với CPU (mặc định).
            Log.i(TAG, "Đang khởi tạo session với CPU...")
            val sessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnvironment!!.createSession(modelBytes, sessionOptions)
            logSuccess(startTime, "CPU")

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi nghiêm trọng: Không thể khởi tạo session ngay cả với CPU.", e)
        }
    }

    /**
     * Hàm trợ giúp để log thông tin khi khởi tạo thành công.
     */
    private fun logSuccess(startTime: Long, providerInfo: String) {
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "Load model ONNX thành công.")
        Log.i(TAG, "Provider đã được cấu hình: $providerInfo")
        Log.i(TAG, "Thời gian load model: $duration ms")

        val inputInfo = ortSession?.inputInfo
        val outputInfo = ortSession?.outputInfo

        Log.i(TAG, "--- Input Info (Chi tiết) ---")
        inputInfo?.forEach { (name, info) ->
            Log.i(TAG, "Name: $name, Info: ${info.info}")
        }

        Log.i(TAG, "--- Output Info (Chi tiết) ---")
        outputInfo?.forEach { (name, info) ->
            Log.i(TAG, "Name: $name, Info: ${info.info}")
        }
    }

    /**
     * Kiểm tra xem model đã được load và sẵn sàng chưa.
     */
    fun isInitialized(): Boolean {
        return ortSession != null
    }

    /**
     * Giải phóng tài nguyên của session.
     * Hãy gọi hàm này khi không cần dùng nữa (ví dụ: trong onDestroy).
     */
    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            // Không cần close ortEnvironment vì nó là singleton được chia sẻ
            ortEnvironment = null
            Log.i(TAG, "Đã đóng session.")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi đóng ONNX session.", e)
        }
    }

    private fun loadModelBytesFromAssets(context: Context, assetName: String): ByteArray {
        context.assets.open(assetName).use { inputStream ->
            return inputStream.readBytes()
        }
    }

    /**
     * Letterbox resize: Maintain aspect ratio and add gray padding
     * Returns: Pair(resized bitmap, scale ratio)
     */
    private fun letterboxResize(bitmap: Bitmap, targetSize: Int): Pair<Bitmap, Float> {
        val width = bitmap.width
        val height = bitmap.height
        
        // Calculate scale ratio
        val scale = min(targetSize.toFloat() / width, targetSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        // Resize image
        val resizedBitmap = bitmap.scale(newWidth, newHeight, filter = true)
        
        // Create canvas with gray padding
        val paddedBitmap = createBitmap(targetSize, targetSize)
        val canvas = android.graphics.Canvas(paddedBitmap)
        
        // Fill with gray color (114, 114, 114)
        canvas.drawRGB(114, 114, 114)
        
        // Calculate padding offsets (center the image)
        val padX = (targetSize - newWidth) / 2
        val padY = (targetSize - newHeight) / 2
        
        // Draw resized image on canvas
        canvas.drawBitmap(resizedBitmap, padX.toFloat(), padY.toFloat(), null)
        
        // Cleanup
        if (resizedBitmap != bitmap) resizedBitmap.recycle()
        
        return Pair(paddedBitmap, scale)
    }

    /**
     * Preprocess bitmap for YOLO11:
     * 1. Letterbox resize to 640x640
     * 2. Normalize to [0, 1]
     * 3. Convert to RGB and CHW format
     * 
     * Returns: Pair(FloatArray [1, 3, 640, 640], scale ratio)
     */
    private fun preprocessBitmap(bitmap: Bitmap): Pair<FloatArray, Float> {
        // Letterbox resize
        val (paddedBitmap, scale) = letterboxResize(bitmap, INPUT_SIZE)
        
        // Get pixels
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        paddedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        // Prepare output array: [1, 3, 640, 640] (NCHW format)
        val floatArray = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val channelSize = INPUT_SIZE * INPUT_SIZE
        
        // Convert ARGB to RGB channels and normalize to [0, 1]
        for (i in pixels.indices) {
            val pixel = pixels[i]
            
            // Extract RGB values (0-255) and normalize to [0, 1]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            // Fill in NCHW format (CHW: Channel, Height, Width)
            floatArray[i] = r                          // R channel
            floatArray[channelSize + i] = g            // G channel
            floatArray[2 * channelSize + i] = b        // B channel
        }
        
        // Cleanup
        paddedBitmap.recycle()
        
        return Pair(floatArray, scale)
    }

    /**
     * Calculate IoU (Intersection over Union) between two boxes
     */
    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        val x1 = max(box1[0], box2[0])
        val y1 = max(box1[1], box2[1])
        val x2 = min(box1[2], box2[2])
        val y2 = min(box1[3], box2[3])
        
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val box2Area = (box2[2] - box2[0]) * (box2[3] - box2[1])
        val unionArea = box1Area + box2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    /**
     * Non-Maximum Suppression (NMS)
     */
    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        // Sort by confidence (descending)
        val sortedDetections = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        
        while (sortedDetections.isNotEmpty()) {
            val best = sortedDetections.removeAt(0)
            result.add(best)
            
            // Remove overlapping detections
            sortedDetections.removeAll { detection ->
                calculateIoU(best.bbox, detection.bbox) > iouThreshold
            }
        }
        
        return result
    }

    /**
     * Postprocess YOLO11 output:
     * 1. Transpose [1, 5, 8400] -> [8400, 5]
     * 2. Filter by confidence threshold
     * 3. Convert xywh -> xyxy
     * 4. Apply NMS
     * 5. Scale back to original image size
     * 
     * @param output Model output [1, 5, 8400]
     * @param scale Scale ratio from letterbox resize
     * @param originalWidth Original image width
     * @param originalHeight Original image height
     * @param confThreshold Confidence threshold
     * @param iouThreshold IoU threshold for NMS
     */
    private fun postprocess(
        output: FloatArray,
        scale: Float,
        originalWidth: Int,
        originalHeight: Int,
        confThreshold: Float,
        iouThreshold: Float
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        // Transpose: [1, 5, 8400] -> [8400, 5]
        // Output format: [cx, cy, w, h, confidence] for each of 8400 proposals
        for (i in 0 until NUM_PROPOSALS) {
            val confidence = output[4 * NUM_PROPOSALS + i]
            
            // Filter by confidence threshold
            if (confidence < confThreshold) continue
            
            // Extract box coordinates (normalized to INPUT_SIZE)
            val cx = output[0 * NUM_PROPOSALS + i]
            val cy = output[1 * NUM_PROPOSALS + i]
            val w = output[2 * NUM_PROPOSALS + i]
            val h = output[3 * NUM_PROPOSALS + i]
            
            // Convert xywh -> xyxy (still in INPUT_SIZE coordinate system)
            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            
            // Calculate padding offsets
            val padX = (INPUT_SIZE - originalWidth * scale) / 2f
            val padY = (INPUT_SIZE - originalHeight * scale) / 2f
            
            // Remove padding and scale back to original size
            val origX1 = max(0f, (x1 - padX) / scale)
            val origY1 = max(0f, (y1 - padY) / scale)
            val origX2 = min(originalWidth.toFloat(), (x2 - padX) / scale)
            val origY2 = min(originalHeight.toFloat(), (y2 - padY) / scale)
            
            detections.add(
                Detection(
                    bbox = floatArrayOf(origX1, origY1, origX2, origY2),
                    confidence = confidence,
                    classId = 0, // Only one class: "card"
                    className = CLASS_NAMES[0]
                )
            )
        }
        
        // Apply NMS
        return nms(detections, iouThreshold)
    }

    /**
     * Main detection function.
     * 
     * @param bitmap Input bitmap
     * @param confThreshold Confidence threshold (default: 0.25)
     * @param iouThreshold IoU threshold for NMS (default: 0.45)
     * @return List of Detection objects, or null if error
     */
    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): List<Detection>? {
        if (!isInitialized()) {
            Log.e(TAG, "Model chưa được khởi tạo. Hãy gọi initialize() trước.")
            return null
        }

        return try {
            // BẮT ĐẦU ĐO THỜI GIAN TỔNG
            val totalStartTime = System.currentTimeMillis()
            
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            
            // BƯỚC 1: PREPROCESS
            val preprocessStartTime = System.currentTimeMillis()
            val (inputData, scale) = preprocessBitmap(bitmap)
            val preprocessDuration = System.currentTimeMillis() - preprocessStartTime
            Log.i(TAG, "Thời gian preprocess: $preprocessDuration ms")
            
            // Create input tensor [1, 3, 640, 640]
            val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(inputData),
                inputShape
            )
            
            // BƯỚC 2: INFERENCE
            val inferenceStartTime = System.currentTimeMillis()
            val output = ortSession!!.run(mapOf("images" to inputTensor))
            val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
            Log.i(TAG, "Thời gian inference: $inferenceDuration ms")
            
            // Extract output: [1, 5, 8400]
            val outputTensor = output[0].value as Array<*>
            val outputData = (outputTensor[0] as Array<*>)
            
            // Flatten to FloatArray
            val flatOutput = FloatArray(5 * NUM_PROPOSALS)
            for (i in 0 until 5) {
                val channelData = outputData[i] as FloatArray
                System.arraycopy(channelData, 0, flatOutput, i * NUM_PROPOSALS, NUM_PROPOSALS)
            }
            
            // Clean up
            inputTensor.close()
            output.close()
            
            // BƯỚC 3: POSTPROCESS
            val postprocessStartTime = System.currentTimeMillis()
            val detections = postprocess(
                flatOutput,
                scale,
                originalWidth,
                originalHeight,
                confThreshold,
                iouThreshold
            )
            val postprocessDuration = System.currentTimeMillis() - postprocessStartTime
            Log.i(TAG, "Thời gian postprocess: $postprocessDuration ms")
            
            // TÍNH TỔNG THỜI GIAN
            val totalDuration = System.currentTimeMillis() - totalStartTime
            Log.i(TAG, "Thời gian tổng: $totalDuration ms")
            Log.i(TAG, "Detected ${detections.size} objects")
            
            // Log chi tiết các detections
            detections.forEachIndexed { index, det ->
                Log.d(TAG, "Detection $index: ${det.className}, conf=${det.confidence}, bbox=[${det.bbox.joinToString(", ")}]")
            }
            
            detections
            
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi detect objects", e)
            null
        }
    }
}
