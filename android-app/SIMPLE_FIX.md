# 🎯 SIMPLE FIX - Éớp UI = Bitmap Size (1280x720)

## ✅ Thay đổi cần làm:

### 1. Lock landscape trong MainActivity.onCreate()
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // ✅ LOCK LANDSCAPE
    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    // ... rest of code
}
```

### 2. XÓA toàn bộ LaunchedEffect matrix calculation
Tìm và XÓA từ dòng:
```kotlin
// ✅ NEW: Monitor imageAnalysis thay đổi + trigger update matrix
```
Đến hết:
```kotlin
Log.e("BBOX_DEBUG", "❌ Failed checks...")
}
```

### 3. XÓA state không cần thiết
```kotlin
// XÓA dòng này:
var transformMatrix by remember { mutableStateOf(Matrix()) }
var sourceImageSize by remember { mutableStateOf(Pair(1, 1)) }
var rotationDegrees by remember { mutableIntStateOf(0) }
```

### 4. Thay đổi BoundingBoxOverlay - VẼ TRỰC TIẾP

```kotlin
@Composable
fun BoundingBoxOverlay(
    recognitionResults: List<RecognitionResult>
) {
    // ✅ FORCE Canvas size = 1280x720 (bitmap size)
    Canvas(
        modifier = Modifier
            .width(1280.dp)
            .height(720.dp)
    ) {
        recognitionResults.forEach { result ->
            val bbox = result.bbox
            
            // ✅ VẼ TRỰC TIẾP - Không transform!
            val x1 = bbox[0] * (size.width / 1280f)
            val y1 = bbox[1] * (size.height / 720f)
            val x2 = bbox[2] * (size.width / 1280f)
            val y2 = bbox[3] * (size.height / 720f)
            
            val boxColor = if (result.category != "Unknown") Color.Green else Color.Red
            
            drawRect(
                color = boxColor,
                topLeft = Offset(x1, y1),
                size = Size(x2 - x1, y2 - y1),
                style = Stroke(width = 3.dp.toPx())
            )
            
            // Label text
            val labelText = "[${result.category}] D:${String.format("%.2f", result.detConf)} R:${String.format("%.2f", result.recConf)}"
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 14.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            
            drawContext.canvas.nativeCanvas.drawText(
                labelText,
                x1 + 4.dp.toPx(),
                y1 - 4.dp.toPx(),
                textPaint
            )
        }
    }
}
```

### 5. Thay đổi Box container trong CameraScreen
```kotlin
// OLD:
Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    BoundingBoxOverlay(recognitionResults, transformMatrix, rotation)
}

// NEW:
Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
) {
    // Preview với size cố định
    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .width(1280.dp)
            .height(720.dp)
    )
    
    // Overlay cùng size
    BoundingBoxOverlay(recognitionResults)
}
```

### 6. XÓA function rotateBboxCoordinates (không cần nữa)

### 7. XÓA 5 test boxes trong onFrameAnalyzed

---

## 🎯 Kết quả mong đợi:
- ✅ App luôn landscape (0°)
- ✅ UI = 1280x720 (đúng bitmap size)
- ✅ Bbox vẽ 1:1, không scale, không transform
- ✅ Đơn giản, dễ debug
