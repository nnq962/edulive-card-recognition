package com.example.card_recognition

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException

class Detector {

    companion object {
        private const val TAG = "Detector"
        private const val MODEL_ASSET_NAME = "yolo11n.onnx"
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
}
