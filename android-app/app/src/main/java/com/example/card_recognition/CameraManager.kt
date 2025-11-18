package com.example.card_recognition // Thay thế bằng package của bạn

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAnalyzed: (ImageProxy) -> Unit // Callback để gửi frame ra ngoài
) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    // Tạo một luồng nền riêng để chạy phân tích, tránh làm giật UI
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Hàm này sẽ được gọi từ MainActivity
    fun startCamera(surfaceProvider: Preview.SurfaceProvider) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                // Lấy camera provider
                cameraProvider = cameraProviderFuture.get()

                // 1. Cấu hình Use Case: Preview (Hiển thị)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = surfaceProvider
                }

                // 2. Cấu hình Use Case: ImageAnalysis (Phân tích frame)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()

                val imageAnalyzer = ImageAnalysis.Builder()
                    // Cấu hình tỷ lệ và độ phân giải với ResolutionSelector (cách mới)
                    .setResolutionSelector(resolutionSelector)
                    // Đảm bảo chỉ lấy frame mới nhất
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor) { image ->
                            // Log độ phân giải của frame
//                            Log.i(TAG, "Frame resolution: ${image.width}x${image.height}")
                            // Đây là nơi frame được gửi đi
                            onFrameAnalyzed(image)
                            // Lưu ý: image.close() sẽ được gọi ở nơi nhận (MainActivity)
                        }
                    }

                // Chọn camera sau
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Hủy liên kết (unbind) mọi use case cũ trước khi liên kết mới
                cameraProvider?.unbindAll()

                // Liên kết các use case với vòng đời (lifecycle)
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview, // Use case để hiển thị
                    imageAnalyzer // Use case để phân tích
                )
                Log.i(TAG, "Camera đã được liên kết với Lifecycle.")

            } catch (exc: Exception) {
                Log.e(TAG, "Liên kết CameraX thất bại", exc)
            }        }, ContextCompat.getMainExecutor(context))
    }

    // Hàm này gọi khi không cần camera nữa
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown() // Tắt luồng nền
            Log.i(TAG, "Camera đã được hủy liên kết (unbind).")
        } catch (e: Exception) {
            Log.e(TAG, "Hủy liên kết CameraX thất bại", e)
        }
    }
}