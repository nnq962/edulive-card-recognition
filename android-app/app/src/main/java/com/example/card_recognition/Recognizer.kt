package com.example.card_recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException
import java.lang.Exception
import java.nio.FloatBuffer
import androidx.core.graphics.scale

class Recognizer {

    companion object {
        private const val TAG = "Recognizer"
        private const val MODEL_ASSET_NAME = "efficientnet_lite0_1280d.onnx"

        // Constants from Python code
        private const val CROP_PADDING = 32
        private const val INPUT_SIZE = 224
        private val MEAN_RGB = floatArrayOf(0.498f, 0.498f, 0.498f)
        private val STDDEV_RGB = floatArrayOf(0.502f, 0.502f, 0.502f)
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
                } catch (e: kotlin.Exception) {
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

        } catch (e: kotlin.Exception) {
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

    /**
     * Preprocess Bitmap theo pipeline của EfficientNet Lite0:
     * 1. Resize to (INPUT_SIZE + CROP_PADDING) với BICUBIC interpolation
     * 2. Center crop về INPUT_SIZE x INPUT_SIZE
     * 3. Normalize: (pixel/255 - MEAN) / STD
     *
     * @param bitmap Input bitmap (RGB)
     * @return FloatArray [1, 3, 224, 224] - NCHW format
     */
    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val resizeSize = INPUT_SIZE + CROP_PADDING // 256

        // Step 1: Resize with BICUBIC (Android's default is BILINEAR, but we'll use filtering=true for better quality)
        val resizedBitmap = bitmap.scale(resizeSize, resizeSize, filter = true)

        // Step 2: Center crop to INPUT_SIZE x INPUT_SIZE
        val cropOffset = CROP_PADDING / 2 // 16
        val croppedBitmap = Bitmap.createBitmap(
            resizedBitmap,
            cropOffset,
            cropOffset,
            INPUT_SIZE,
            INPUT_SIZE
        )

        // Step 3: Convert to float array and normalize
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        croppedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Prepare output array: [1, 3, 224, 224] (NCHW format)
        val floatArray = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)

        // Convert ARGB to RGB channels and normalize
        // NCHW format: [batch, channel, height, width]
        val channelSize = INPUT_SIZE * INPUT_SIZE

        for (i in pixels.indices) {
            val pixel = pixels[i]

            // Extract RGB values (0-255)
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // Normalize: (value - mean) / std
            val rNorm = (r - MEAN_RGB[0]) / STDDEV_RGB[0]
            val gNorm = (g - MEAN_RGB[1]) / STDDEV_RGB[1]
            val bNorm = (b - MEAN_RGB[2]) / STDDEV_RGB[2]

            // Calculate correct position in NCHW format
            // pixels[i] is read in row-major order: [h=0,w=0], [h=0,w=1], ..., [h=0,w=223], [h=1,w=0], ...
            val h = i / INPUT_SIZE
            val w = i % INPUT_SIZE
            val baseIdx = h * INPUT_SIZE + w

            // Fill in NCHW format: [batch=0, channel, height, width]
            floatArray[0 + baseIdx] = rNorm  // R channel
            floatArray[1 * channelSize + baseIdx] = gNorm  // G channel
            floatArray[2 * channelSize + baseIdx] = bNorm  // B channel
        }

        // Clean up
        if (resizedBitmap != bitmap) resizedBitmap.recycle()
        if (croppedBitmap != bitmap && croppedBitmap != resizedBitmap) croppedBitmap.recycle()

        return floatArray
    }

    /**
     * Extract 1280D embedding từ input bitmap.
     *
     * @param bitmap Input bitmap (sẽ được preprocess tự động)
     * @return FloatArray với size 1280 (embedding vector), hoặc null nếu có lỗi
     */
    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        if (!isInitialized()) {
            Log.e(TAG, "Model chưa được khởi tạo. Hãy gọi initialize() trước.")
            return null
        }

        return try {
            // BẮT ĐẦU ĐO THỜI GIAN TỔNG
            val totalStartTime = System.currentTimeMillis()

            // BẮT ĐẦU ĐO THỜI GIAN PREPROCESSING
            val preprocessStartTime = System.currentTimeMillis()
            val inputData = preprocessBitmap(bitmap)
            val preprocessDuration = System.currentTimeMillis() - preprocessStartTime
            Log.i(TAG, "Thời gian preprocessing: $preprocessDuration ms")

            // Create input tensor [1, 3, 224, 224]
            val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(inputData),
                inputShape
            )

            // BẮT ĐẦU ĐO THỜI GIAN INFERENCE
            val inferenceStartTime = System.currentTimeMillis()

            // Run inference
            val output = ortSession!!.run(mapOf("input" to inputTensor))

            val inferenceDuration = System.currentTimeMillis() - inferenceStartTime
            Log.i(TAG, "Thời gian inference: $inferenceDuration ms")

            // Extract embedding from output
            // Output shape: [1, 1280]
            val outputTensor = output[0].value as Array<*>
            val embedding = (outputTensor[0] as FloatArray)

            // Clean up
            inputTensor.close()
            output.close()

            // TÍNH TỔNG THỜI GIAN
            val totalDuration = System.currentTimeMillis() - totalStartTime
            Log.i(TAG, "Thời gian tổng (preprocessing + inference): $totalDuration ms")
            Log.i(TAG, "Embedding extracted: size = ${embedding.size}")

            embedding

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi extract embedding", e)
            null
        }
    }

    private fun loadModelBytesFromAssets(context: Context, assetName: String): ByteArray {
        context.assets.open(assetName).use { inputStream ->
            return inputStream.readBytes()
        }
    }
}