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
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.card_recognition.ui.theme.CardrecognitionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Matrix
import android.graphics.RectF

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. Khai báo một thuộc tính để giữ đối tượng Recognizer và Detector
    private lateinit var recognizer: Recognizer
    private lateinit var detector: Detector
    private var isRecognizerReady by mutableStateOf(false)
    private var isDetectorReady by mutableStateOf(false)
    private var detHardwareInfo by mutableStateOf("Loading...")
    private var recHardwareInfo by mutableStateOf("Loading...")


    // Biến trạng thái để theo dõi quyền camera
    private var hasCameraPermission by mutableStateOf(false)

    // Trình khởi chạy (launcher) để xin quyền
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            Log.i(TAG, "Quyền Camera đã được cấp.")
        } else {
            Log.w(TAG, "Quyền Camera bị từ chối.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // REMOVED: Lock landscape - để tự động rotate

        // 2. Khởi tạo Recognizer và Detector
        recognizer = Recognizer()
        detector = Detector()
        // Cấu hình phần cứng mong muốn
        val useNNAPIForDet = true // Ví dụ: Detector dùng CPU
        val useNNAPIForRec = true  // Ví dụ: Recognizer dùng NNAPI

        // 3. ✅ GỌI TRÊN LUỒNG NỀN (IO) BẰNG COROUTINE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // this@MainActivity là context của Activity
                detector.initialize(this@MainActivity, useNNAPI = useNNAPIForDet)
                recognizer.initialize(this@MainActivity, useNNAPI = useNNAPIForRec)

                // 2. Cập nhật text hiển thị sau khi init thành công
                isDetectorReady = true
                isRecognizerReady = true
                detHardwareInfo = if (useNNAPIForDet) "NNAPI" else "CPU"
                recHardwareInfo = if (useNNAPIForRec) "NNAPI" else "CPU"

                Log.i(TAG, "Models đã khởi tạo thành công.")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi khởi tạo model", e)
            }
        }

        // Kiểm tra quyền camera khi khởi động
        checkCameraPermission()

        enableEdgeToEdge()
        setContent {
            CardrecognitionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        // 3. Hiển thị nội dung dựa trên trạng thái quyền
                        if (hasCameraPermission) {
                            // Nếu có quyền, hiển thị Camera
                            CameraScreen(
                                recognizer = recognizer,
                                detector = detector,
                                isDetectorReady = isDetectorReady,
                                isRecognizerReady = isRecognizerReady,
                                detHardware = detHardwareInfo, // Truyền vào
                                recHardware = recHardwareInfo  // Truyền vào

                            )
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
     * Convert ImageProxy to Bitmap WITHOUT rotation
     */
    internal fun imageProxyToBitmap(image: androidx.camera.core.ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}

// Composable để hiển thị camera và xử lý frame
@Composable
fun CameraScreen(
    recognizer: Recognizer,
    detector: Detector,
    isDetectorReady: Boolean,
    isRecognizerReady: Boolean,
    detHardware: String, // Nhận thông tin Hardware
    recHardware: String
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // State lưu trữ danh sách bounding box và kích thước ảnh gốc để vẽ
    var detections by remember { mutableStateOf(emptyList<Detector.Detection>()) }
    var sourceImageSize by remember { mutableStateOf(Pair(1, 1)) } // width, height
    var isProcessing by remember { mutableStateOf(false) } // Flag để skip frames
    var frameCount by remember { mutableIntStateOf(0) }
    var detectionTime by remember { mutableStateOf(0L) } // Thời gian detection (ms)
    var recTime by remember { mutableStateOf(0L) } // Thời gian recognition (ms)
    var rotationDegrees by remember { mutableStateOf(0) } // Camera rotation

    // 1. Tạo PreviewView (View truyền thống) để CameraX hiển thị
    val previewView = remember {
        PreviewView(context).apply {
            // QUAN TRỌNG: Ép buộc hiển thị kiểu Lấp đầy và Căn giữa
            // Để khớp với logic tính toán offset trong BoundingBoxOverlay
            scaleType = PreviewView.ScaleType.FILL_CENTER

            // Đảm bảo render tương thích để tránh bị méo trên một số thiết bị
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // 2. Tạo CameraManager và quản lý vòng đời của nó
    val cameraManager = remember {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrameAnalyzed = { imageProxy ->
                frameCount++
                
                // SKIP FRAME nếu đang xử lý hoặc detector chưa sẵn sàng
                if (isProcessing || !detector.isInitialized()) {
                    Log.d("CameraScreen", "Skipping frame $frameCount - processing: $isProcessing")
                    imageProxy.close()
                    return@CameraManager
                }
                
                isProcessing = true
                
                // Xử lý trên background thread
                (context as ComponentActivity).lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        Log.d("CameraScreen", "Processing frame $frameCount")
                        
                        // Chuyển đổi ImageProxy sang Bitmap KHÔNG ROTATE
                        val mainActivity = context as MainActivity
                        val bitmap = mainActivity.imageProxyToBitmap(imageProxy)
                        
                        // Lưu rotation degrees để transform bbox sau
                        val currentRotation = imageProxy.imageInfo.rotationDegrees
                        
                        val imgWidth = bitmap.width
                        val imgHeight = bitmap.height
                        
                        Log.d("CameraScreen", "Bitmap size: ${imgWidth}x${imgHeight}, rotation: $currentRotation")

                        // Gọi detection và đo thời gian
                        val detectionStartTime = System.currentTimeMillis()
                        val results = detector.detect(bitmap, 0.80f)
                        val detectionDuration = System.currentTimeMillis() - detectionStartTime

                        // Cập nhật State
                        withContext(Dispatchers.Main) {
                            if (results != null && results.isNotEmpty()) {
                                detections = results
                                sourceImageSize = Pair(imgWidth, imgHeight)
                                detectionTime = detectionDuration
                                rotationDegrees = currentRotation
                                Log.d("CameraScreen", "Detected ${results.size} objects in ${detectionDuration}ms")
                            } else {
                                detections = emptyList()
                                detectionTime = detectionDuration
                                rotationDegrees = currentRotation
                            }
                        }
                        
                        // Cleanup
                        bitmap.recycle()
                        
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Error processing frame", e)
                    } finally {
                        imageProxy.close()
                        isProcessing = false
                        Log.d("CameraScreen", "Frame $frameCount processing complete")
                    }
                }
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

    // Container chứa Camera Preview và Overlay
    Box(modifier = Modifier.fillMaxSize()) {
        // 5. Sử dụng AndroidView để nhúng PreviewView vào Compose
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 6. Lớp phủ vẽ Bounding Box
        BoundingBoxOverlay(
            detections = detections,
            sourceWidth = sourceImageSize.first,
            sourceHeight = sourceImageSize.second,
            rotationDegrees = rotationDegrees
        )

        // 7. PANEL THÔNG TIN DEBUG (Góc trên trái)
        DebugInfoPanel(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp), // Cách lề một chút
            isDetectorReady = isDetectorReady,
            isRecognizerReady = isRecognizerReady,
            detTimeMs = detectionTime,
            detHardware = detHardware,
            recTimeMs = recTime,
            recHardware = recHardware
        )
    }
}

@Composable
fun BoundingBoxOverlay(
    detections: List<Detector.Detection>,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Tính kích thước "ảo" sau khi xoay ảnh gốc
        // Ví dụ: Ảnh gốc 1280x720, Xoay 90 -> "ảo" là 720x1280
        val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
            90, 270 -> Pair(sourceHeight.toFloat(), sourceWidth.toFloat())
            else -> Pair(sourceWidth.toFloat(), sourceHeight.toFloat())
        }

        // 2. Tính tỷ lệ scale để lấp đầy màn hình (FILL)
        // Dùng MAX để đảm bảo ảnh phủ kín màn hình (giống behavior của Camera Preview)
        val scaleX = canvasWidth / rotatedWidth
        val scaleY = canvasHeight / rotatedHeight
        val scale = kotlin.math.max(scaleX, scaleY)

        // 3. Tính Offset để căn giữa hình ảnh vào màn hình
        val scaledWidth = rotatedWidth * scale
        val scaledHeight = rotatedHeight * scale
        val offsetX = (canvasWidth - scaledWidth) / 2
        val offsetY = (canvasHeight - scaledHeight) / 2

        if (detections.isNotEmpty()) {
            Log.d("BoundingBox", "--- FRAME START ---")
            Log.d("BoundingBox", "Canvas: ${canvasWidth}x${canvasHeight}")
            Log.d("BoundingBox", "Source: ${sourceWidth}x${sourceHeight} (Rot: $rotationDegrees)")
            Log.d("BoundingBox", "Rotated Source: ${rotatedWidth}x${rotatedHeight}")
            Log.d("BoundingBox", "Scale: $scale (ScaleX: $scaleX, ScaleY: $scaleY)")
            Log.d("BoundingBox", "Offset: X=$offsetX, Y=$offsetY")
        }

        // 4. Thiết lập Ma trận biến đổi (Matrix)
        val matrix = Matrix()

        // B1: Đưa về gốc (0,0) và Xoay
        // Lưu ý: postRotate xoay quanh (0,0)
        matrix.postRotate(rotationDegrees.toFloat())

        // B2: Dịch chuyển ảnh về vùng dương sau khi xoay (nếu cần)
        when (rotationDegrees) {
            90 -> matrix.postTranslate(rotatedWidth, 0f)   // Xoay 90: (h, w) -> đẩy x thêm h (vì h là width mới)
            180 -> matrix.postTranslate(rotatedWidth, rotatedHeight)
            270 -> matrix.postTranslate(0f, rotatedHeight)
        }

        // B3: Scale phóng to
        matrix.postScale(scale, scale)

        // B4: Dịch chuyển vào giữa màn hình
        matrix.postTranslate(offsetX, offsetY)

        detections.forEachIndexed { index, detection ->
            val bbox = detection.bbox // [x1, y1, x2, y2] trên ảnh gốc (landscape)
            val rect = RectF(bbox[0], bbox[1], bbox[2], bbox[3])

            // Log trước khi map
            val originalStr = "[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]"

            // Áp dụng ma trận
            matrix.mapRect(rect)

            // Log sau khi map
            Log.d("BoundingBox", "Det #$index: Orig=$originalStr")
            Log.d("BoundingBox", "       -> Screen=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]")

            // Vẽ
            drawRect(
                color = Color.Green,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
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
@Composable
fun DebugInfoPanel(
    modifier: Modifier = Modifier,
    isDetectorReady: Boolean,   // <--- Nhận biến riêng 1
    isRecognizerReady: Boolean, // <--- Nhận biến riêng 2
    detTimeMs: Long,
    detHardware: String,
    recTimeMs: Long,
    recHardware: String
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.5f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Dòng 1: Detector Info (Truyền biến isDetectorReady)
            DebugLine(
                label = "DET",
                isReady = isDetectorReady, // <--- Check riêng ở đây
                timeMs = detTimeMs,
                hardware = detHardware
            )

            // Dòng 2: Recognizer Info (Truyền biến isRecognizerReady)
            DebugLine(
                label = "REC",
                isReady = isRecognizerReady, // <--- Check riêng ở đây
                timeMs = recTimeMs,
                hardware = recHardware
            )
        }
    }
}

@Composable
fun DebugLine(
    label: String,
    isReady: Boolean, // <--- Thêm tham số này vào DebugLine
    timeMs: Long,
    hardware: String
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Luôn hiển thị Nhãn (VD: "DET: ") để bố cục thẳng hàng
        Text(
            text = "$label: ",
            style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        )

        if (!isReady) {
            // 2a. Nếu chưa ready -> Hiển thị Loading màu vàng
            Text(
                text = "Loading...",
                style = TextStyle(
                    color = Color.Yellow,
                    fontSize = 14.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            )
        } else {
            // 2b. Nếu đã ready -> Hiển thị Thời gian + Hardware như cũ
            val timeColor = if (timeMs > 100) Color(0xFFFFE082) else Color(0xFF69F0AE)
            Text(
                text = String.format("%.3fs ", timeMs / 1000f),
                style = TextStyle(color = timeColor, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
            )

            val hwColor = if (hardware.contains("NNAPI")) Color.Cyan else Color.LightGray
            Text(
                text = "[$hardware]",
                style = TextStyle(color = hwColor, fontSize = 12.sp)
            )
        }
    }
}