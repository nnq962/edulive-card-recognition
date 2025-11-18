package com.example.card_recognition

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException // Thêm import này
import java.lang.Exception // Thêm import này

class Recognizer {

    companion object {
        private const val TAG = "Recognizer"
        private const val MODEL_ASSET_NAME = "efficientnet_lite0_1280d.onnx"
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /**
     * Khởi tạo OrtEnvironment và OrtSession để load model.
     * Hãy gọi hàm này từ Activity hoặc ViewModel của bạn (ví dụ: trong onCreate).
     */
    fun initialize(context: Context, useNNAPI: Boolean = true) {
        // Chỉ khởi tạo một lần
        if (ortSession != null) {
            Log.d(TAG, "Session đã được khởi tạo.")
            return
        }

        // BẮT ĐẦU ĐO THỜI GIAN
        val startTime = System.currentTimeMillis()

        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()

            // BỔ SUNG 1: BẬT/TẮT NNAPI DỰA TRÊN FLAG
            var providerInfo = "CPU (Mặc định)"
            if (useNNAPI) {
                try {
                    sessionOptions.addNnapi()
                    providerInfo = "NNAPI (Đã yêu cầu)"
                    Log.i(TAG, "Đã yêu cầu sử dụng NNAPI.")
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể bật NNAPI (thiết bị/model không hỗ trợ).", e)
                    providerInfo = "CPU (Không thể bật NNAPI)" // Log rõ hơn
                }
            } else {
                Log.i(TAG, "Đã chủ động không sử dụng NNAPI (chỉ dùng CPU).")
            }
            // KẾT THÚC BỔ SUNG 1

            Log.i(TAG, "Đang load model: $MODEL_ASSET_NAME")
            val modelBytes = loadModelBytesFromAssets(context, MODEL_ASSET_NAME)

            ortSession = ortEnvironment!!.createSession(modelBytes, sessionOptions)

            // BỔ SUNG 2: TÍNH TOÁN VÀ LOG THỜI GIAN
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Load model ONNX thành công.")
            Log.i(TAG, "Provider đã được cấu hình: $providerInfo")
            Log.i(TAG, "Thời gian load model: $duration ms") // <-- LOG MỚI
            // KẾT THÚC BỔ SUNG 2

            // Phần log chi tiết (Giữ nguyên)
            val inputInfo = ortSession?.inputInfo
            val outputInfo = ortSession?.outputInfo

            Log.i(TAG, "--- Input Info (Chi tiết) ---")
            inputInfo?.forEach { (name, info) ->
                Log.i(TAG, "Name: $name")
                Log.i(TAG, "Info: ${info.info.toString()}")
            }

            Log.i(TAG, "--- Output Info (Chi tiết) ---")
            outputInfo?.forEach { (name, info) ->
                Log.i(TAG, "Name: $name")
                Log.i(TAG, "Info: ${info.info.toString()}")
            }

        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi đọc file model từ assets.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi khởi tạo ONNX session.", e)
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