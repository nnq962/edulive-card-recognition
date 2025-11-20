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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.nativeCanvas
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. Khai báo một thuộc tính để giữ đối tượng Recognizer và Detector
    private lateinit var recognizer: Recognizer
    private lateinit var detector: Detector
    var embeddingData: EmbeddingData? by mutableStateOf(null)  // Thêm embeddingData
    
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
        val useNNAPIForDet = false // Ví dụ: Detector dùng CPU
        val useNNAPIForRec = true  // Ví dụ: Recognizer dùng NNAPI

        // 3. ✅ GỌI TRÊN LUỒNG NỀN (IO) BẰNG COROUTINE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // this@MainActivity là context của Activity
                detector.initialize(this@MainActivity, useNNAPI = useNNAPIForDet)
                recognizer.initialize(this@MainActivity, useNNAPI = useNNAPIForRec)
                
                // Load embedding data
                val data = EmbeddingData.loadFromAssets(this@MainActivity, "data.json")

                // 2. Cập nhật text hiển thị sau khi init thành công (trên Main thread)
                withContext(Dispatchers.Main) {
                    embeddingData = data
                    isDetectorReady = true
                    isRecognizerReady = true
                    detHardwareInfo = if (useNNAPIForDet) "NNAPI" else "CPU"
                    recHardwareInfo = if (useNNAPIForRec) "NNAPI" else "CPU"
                    
                    if (data != null) {
                        Log.i(TAG, "Models + EmbeddingData đã khởi tạo thành công (${data.getCategoryCount()} categories)")
                    } else {
                        Log.w(TAG, "Models init OK, nhưng EmbeddingData load failed")
                    }
                }
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

// Data class để lưu kết quả recognition
@Suppress("ArrayInDataClass")
data class RecognitionResult(
    val category: String,
    val detConf: Float,
    val recConf: Float,
    val bbox: FloatArray
)

// Composable để hiển thị camera và xử lý frame
@Composable
fun CameraScreen(
    recognizer: Recognizer,
    detector: Detector,
    isDetectorReady: Boolean,
    isRecognizerReady: Boolean,
    detHardware: String,
    recHardware: String
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    // Lấy embeddingData từ MainActivity
    val mainActivity = context as MainActivity
    val embeddingData = mainActivity.embeddingData


    // State lưu trữ danh sách bounding box và kích thước ảnh gốc để vẽ
    var recognitionResults by remember { mutableStateOf(emptyList<RecognitionResult>()) }
    var sourceImageSize by remember { mutableStateOf(Pair(1, 1)) } // width, height
    var isProcessing by remember { mutableStateOf(false) } // Flag để skip frames
    var frameCount by remember { mutableIntStateOf(0) }
    var detectionTime by remember { mutableLongStateOf(0L) } // Thời gian detection (ms)
    var recTime by remember { mutableLongStateOf(0L) } // Thời gian recognition (ms)
    var rotationDegrees by remember { mutableIntStateOf(0) } // Camera rotation

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
    // QUAN TRỌNG: Thêm embeddingData vào key để recreate khi embeddingData thay đổi
    val cameraManager = remember(embeddingData) {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrameAnalyzed = { imageProxy ->
                frameCount++
                
                // SKIP FRAME nếu đang xử lý hoặc chưa sẵn sàng
                if (isProcessing || !detector.isInitialized() || !recognizer.isInitialized() || mainActivity.embeddingData == null) {
                    imageProxy.close()
                    return@CameraManager
                }
                
                isProcessing = true
                
                // Xử lý trên background thread
                (context as ComponentActivity).lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        Log.d("CameraScreen", "Processing frame $frameCount")
                        
                        // Chuyển đổi ImageProxy sang Bitmap KHÔNG ROTATE
                        // val mainActivity = context as MainActivity  <-- REMOVED (Không cần cast lại vì đã có ở trên)
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

                        // Recognition pipeline (chỉ chạy nếu có embeddingData)
                        val recognitionStartTime = System.currentTimeMillis()
                        val recResults = mutableListOf<RecognitionResult>()
                        
                        // ⚠️ CRITICAL: Phải lấy embeddingData trực tiếp từ mainActivity
                        val currentEmbeddingData = mainActivity.embeddingData
                        Log.d("CameraScreen", "Starting recognition: results=${results?.size}, embeddingData=${currentEmbeddingData != null}")
                        
                        if (results != null && results.isNotEmpty() && currentEmbeddingData != null) {
                            results.forEach { detection ->
                                try {
                                    // Crop bbox KHÔNG padding (giống Python)
                                    val x1 = detection.bbox[0].toInt().coerceAtLeast(0)
                                    val y1 = detection.bbox[1].toInt().coerceAtLeast(0)
                                    val x2 = detection.bbox[2].toInt().coerceAtMost(imgWidth)
                                    val y2 = detection.bbox[3].toInt().coerceAtMost(imgHeight)

                                    val width = x2 - x1
                                    val height = y2 - y1

                                    if (width > 0 && height > 0) {
                                        val croppedBitmap = Bitmap.createBitmap(bitmap, x1, y1, width, height)

                                        // Extract embedding
                                        val embedding = recognizer.extractEmbedding(croppedBitmap)
                                        croppedBitmap.recycle()

                                        if (embedding != null) {
                                            // Find best match
                                            val matchResult = currentEmbeddingData.findBestMatch(
                                                queryEmbedding = embedding,
                                                threshold = 0.75f
                                            )

                                            val category = matchResult.category ?: "Unknown"
                                            recResults.add(
                                                RecognitionResult(
                                                    category = category,
                                                    detConf = detection.confidence,
                                                    recConf = matchResult.similarity,
                                                    bbox = detection.bbox
                                                )
                                            )
                                            Log.d("CameraScreen", "Recognized: $category (det=${detection.confidence}, rec=${matchResult.similarity})")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Error in recognition for detection", e)
                                }
                            }
                        }
                        
                        val recognitionDuration = System.currentTimeMillis() - recognitionStartTime

                        // Cập nhật State
                        withContext(Dispatchers.Main) {
                            if (results != null && results.isNotEmpty()) {
                                recognitionResults = recResults
                                sourceImageSize = Pair(imgWidth, imgHeight)
                                detectionTime = detectionDuration
                                recTime = recognitionDuration
                                rotationDegrees = currentRotation
                                Log.d("CameraScreen", "Detected ${results.size} objects in ${detectionDuration}ms, Recognized ${recResults.size} in ${recognitionDuration}ms")
                            } else {
                                recognitionResults = emptyList()
                                detectionTime = detectionDuration
                                recTime = 0L
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
            recognitionResults = recognitionResults,
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
    recognitionResults: List<RecognitionResult>,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Tính kích thước "ảo" sau khi xoay ảnh gốc
        val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
            90, 270 -> Pair(sourceHeight.toFloat(), sourceWidth.toFloat())
            else -> Pair(sourceWidth.toFloat(), sourceHeight.toFloat())
        }

        // 2. Tính tỷ lệ scale để lấp đầy màn hình (FILL)
        val scaleX = canvasWidth / rotatedWidth
        val scaleY = canvasHeight / rotatedHeight
        val scale = kotlin.math.max(scaleX, scaleY)

        // 3. Tính Offset để căn giữa hình ảnh vào màn hình
        val scaledWidth = rotatedWidth * scale
        val scaledHeight = rotatedHeight * scale
        val offsetX = (canvasWidth - scaledWidth) / 2
        val offsetY = (canvasHeight - scaledHeight) / 2

        // 4. Thiết lập Ma trận biến đổi (Matrix)
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        when (rotationDegrees) {
            90 -> matrix.postTranslate(rotatedWidth, 0f)
            180 -> matrix.postTranslate(rotatedWidth, rotatedHeight)
            270 -> matrix.postTranslate(0f, rotatedHeight)
        }
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)

        // 5. Vẽ bounding boxes và labels
        recognitionResults.forEach { result ->
            val bbox = result.bbox
            val rect = RectF(bbox[0], bbox[1], bbox[2], bbox[3])
            matrix.mapRect(rect)

            // Xác định màu: Xanh lá (match) / Đỏ (unknown)
            val boxColor = if (result.category != "Unknown") Color.Green else Color.Red
            
            // Vẽ bbox
            drawRect(
                color = boxColor,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                style = Stroke(width = 3.dp.toPx())
            )
            
            // Vẽ label background (semi-transparent black)
            val labelText1 = "[${result.category}]"
            val labelText2 = "[D: %.2f] [R: %.2f]".format(result.detConf, result.recConf)
            
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 14.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            
            val labelWidth1 = textPaint.measureText(labelText1)
            val labelWidth2 = textPaint.measureText(labelText2)
            val maxLabelWidth = kotlin.math.max(labelWidth1, labelWidth2)
            val labelHeight = textPaint.textSize * 2.5f
            val padding = 4.dp.toPx()
            
            // Vẽ background cho label
            drawRect(
                color = Color.Black.copy(alpha = 0.7f),
                topLeft = Offset(rect.left, rect.top - labelHeight - padding),
                size = Size(maxLabelWidth + padding * 2, labelHeight + padding)
            )
            
            // Vẽ text line 1 (category)
            drawContext.canvas.nativeCanvas.drawText(
                labelText1,
                rect.left + padding,
                rect.top - labelHeight + textPaint.textSize - padding,
                textPaint
            )
            
            // Vẽ text line 2 (confidences)
            drawContext.canvas.nativeCanvas.drawText(
                labelText2,
                rect.left + padding,
                rect.top - padding,
                textPaint
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
// Trong DebugInfoPanel
            DebugLine(
                label = "DET:",  // Không cần thêm dấu cách thừa nữa
                isReady = isDetectorReady,
                timeMs = detTimeMs,
                hardware = "[$detHardware]"
            )

            DebugLine(
                label = "REC:",
                isReady = isRecognizerReady,
                timeMs = recTimeMs,
                hardware = "[$recHardware]"
            )

            DebugLine(
                label = "SUM:", // Label dài nhất này sẽ quyết định độ rộng cần thiết
                isReady = true,
                timeMs = detTimeMs + recTimeMs,
                hardware = ""
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
            text = label.trim(), // Có thể bỏ các dấu cách thừa đi vì đã dùng width
            modifier = Modifier.width(40.dp), // <--- Ép chiều rộng cố định (tùy chỉnh số này)
            style = TextStyle(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
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
                text = String.format(Locale.US, "%.3fs ", timeMs / 1000f),
                style = TextStyle(color = timeColor, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
            )

            val hwColor = if (hardware.contains("NNAPI")) Color.Cyan else Color.LightGray
            Text(
                text = hardware,
                style = TextStyle(color = hwColor, fontSize = 12.sp)
            )
        }
    }
}