package com.example.card_recognition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.card_recognition.ui.theme.CardrecognitionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. Khai báo một thuộc tính để giữ đối tượng Recognizer và Detector
    private lateinit var recognizer: Recognizer
    private lateinit var detector: Detector

    // Biến trạng thái để theo dõi quyền camera
    private var hasCameraPermission by mutableStateOf(false)

    // Trình khởi chạy (launcher) để xin quyền
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            Log.i(TAG, "Quyền Camera đã được cấp.")
            // Bạn có thể không cần làm gì ở đây, Composable sẽ tự cập nhật
        } else {
            Log.w(TAG, "Quyền Camera bị từ chối.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Khởi tạo Recognizer và Detector
        recognizer = Recognizer()
        Log.i(TAG, "Recognizer instance created.")
        detector = Detector()
        Log.i(TAG, "Detector instance created.")

        // 3. ✅ GỌI TRÊN LUỒNG NỀN (IO) BẰNG COROUTINE
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Bắt đầu khởi tạo Recognizer và Detector trên luồng IO...")
            try {
                // this@MainActivity là context của Activity
                recognizer.initialize(this@MainActivity, useNNAPI = true)
                Log.i(TAG, "Recognizer đã khởi tạo thành công.")

                detector.initialize(this@MainActivity, useNNAPI = true)
                Log.i(TAG, "Detector đã khởi tạo thành công.")

                // TEST: Extract embedding từ parrot.png
                testExtractEmbedding()

                // TEST: Detect objects từ parrot.png
                testDetection()

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi khởi tạo model", e)
            }
        }

        // Kiểm tra quyền camera khi khởi động
        checkCameraPermission()

        // Dòng log này sẽ chạy NGAY LẬP TỨC
        // mà không cần chờ recognizer.initialize() xong
        Log.i(TAG, "onCreate đã hoàn tất trên luồng Main.")

        enableEdgeToEdge()
        setContent {
            CardrecognitionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        // 3. Hiển thị nội dung dựa trên trạng thái quyền
                        if (hasCameraPermission) {
                            // Nếu có quyền, hiển thị Camera
                            CameraScreen(recognizer = recognizer, detector = detector)
                        } else {
                            // Nếu không, hiển thị nút xin quyền
                            PermissionRequestScreen {
                                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Load bitmap từ assets folder
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val inputStream = assets.open(fileName)
            BitmapFactory.decodeStream(inputStream).also {
                inputStream.close()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi load bitmap từ assets: $fileName", e)
            null
        }
    }

    /**
     * Test function: Extract embedding từ test.jpg
     */
    private fun testExtractEmbedding() {
        Log.i(TAG, "=== BẮT ĐẦU TEST EXTRACT EMBEDDING ===")

        val testBitmap = loadBitmapFromAssets("parrot.png")

        if (testBitmap == null) {
            Log.e(TAG, "Không thể load test.jpg từ assets")
            return
        }

        Log.i(TAG, "Loaded test.jpg: ${testBitmap.width}x${testBitmap.height}")

        // Extract embedding
        val embedding = recognizer.extractEmbedding(testBitmap)

        if (embedding != null) {
            Log.i(TAG, "✅ Extract embedding THÀNH CÔNG!")
            Log.i(TAG, "Embedding size: ${embedding.size}")
            Log.i(TAG, "First 10 values: ${embedding.take(10).joinToString(", ")}")

            // Tính norm của embedding để verify
            val norm = kotlin.math.sqrt(embedding.map { it * it }.sum())
            Log.i(TAG, "Embedding norm: $norm")
        } else {
            Log.e(TAG, "❌ Extract embedding THẤT BẠI")
        }

        // Clean up
        testBitmap.recycle()
        Log.i(TAG, "=== KẾT THÚC TEST ===")
    }

    /**
     * Test function: Detect objects từ parrot.png
     */
    private fun testDetection() {
        Log.i(TAG, "=== BẮT ĐẦU TEST DETECTION ===")

        val testBitmap = loadBitmapFromAssets("parrot.png")

        if (testBitmap == null) {
            Log.e(TAG, "Không thể load parrot.png từ assets")
            return
        }

        Log.i(TAG, "Loaded parrot.png: ${testBitmap.width}x${testBitmap.height}")

        // Detect objects
        val detections = detector.detect(testBitmap, confThreshold = 0.25f, iouThreshold = 0.45f)

        if (detections != null) {
            Log.i(TAG, "✅ Detection THÀNH CÔNG!")
            Log.i(TAG, "Số lượng objects: ${detections.size}")
            
            detections.forEachIndexed { index, detection ->
                Log.i(TAG, "--- Detection $index ---")
                Log.i(TAG, "  Class: ${detection.className}")
                Log.i(TAG, "  Confidence: ${detection.confidence}")
                Log.i(TAG, "  BBox: [${detection.bbox.joinToString(", ")}]")
            }
            
            // So sánh với Python result:
            Log.i(TAG, "")
            Log.i(TAG, "=== SO SÁNH VỚI PYTHON ===")
            Log.i(TAG, "Python result: 640x576 1 card, 79.8ms")
            Log.i(TAG, "Android result: ${testBitmap.width}x${testBitmap.height} ${detections.size} card")
        } else {
            Log.e(TAG, "❌ Detection THẤT BẠI")
        }

        // Clean up
        testBitmap.recycle()
        Log.i(TAG, "=== KẾT THÚC TEST DETECTION ===")
    }
}

// Composable để hiển thị camera và xử lý frame
@Composable
fun CameraScreen(recognizer: Recognizer, detector: Detector) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. Tạo PreviewView (View truyền thống) để CameraX hiển thị
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // 2. Tạo CameraManager và quản lý vòng đời của nó
    val cameraManager = remember {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrameAnalyzed = { image ->
                // ĐÂY LÀ NƠI PHÉP MÀU XẢY RA

                // Gọi model YOLO để phát hiện đối tượng
                // if (detector.isInitialized()) { // Giả sử có hàm isInitialized
                //    val results = detector.detect(image)
                //    // Xử lý kết quả phát hiện ở đây...
                // }

                // Rất quan trọng: Phải đóng image sau khi dùng xong
                // để CameraX gửi frame tiếp theo.
                image.close()
            }
        )
    }

    // 3. Sử dụng LaunchedEffect để khởi động camera khi Composable xuất hiện
    LaunchedEffect(previewView) {
        cameraManager.startCamera(previewView.surfaceProvider)
    }

    // 4. Sử dụng DisposableEffect để dừng camera khi Composable biến mất
    DisposableEffect(Unit) {
        onDispose {
            cameraManager.stopCamera()
        }
    }

    // 5. Sử dụng AndroidView để nhúng PreviewView vào Compose
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

// Composable đơn giản để xin quyền
@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(onClick = onRequestPermission) {
            Text(text = "Cấp quyền Camera")
        }
    }
}
