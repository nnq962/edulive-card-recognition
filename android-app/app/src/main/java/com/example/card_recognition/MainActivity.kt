package com.example.card_recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.card_recognition.ui.theme.CardrecognitionTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException


class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. Khai báo một thuộc tính để giữ đối tượng Recognizer
    private lateinit var recognizer: Recognizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Khởi tạo Recognizer
        recognizer = Recognizer()
        Log.i(TAG, "Recognizer instance created.")

        // 3. ✅ GỌI TRÊN LUỒNG NỀN (IO) BẰNG COROUTINE
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Bắt đầu khởi tạo Recognizer trên luồng IO...")
            try {
                // this@MainActivity là context của Activity
                recognizer.initialize(this@MainActivity, useNNAPI = true)
                Log.i(TAG, "Recognizer đã khởi tạo thành công.")

                // TEST: Extract embedding từ parrot.png
                testExtractEmbedding()

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi khởi tạo Recognizer", e)
            }
        }

        // Dòng log này sẽ chạy NGAY LẬP TỨC
        // mà không cần chờ recognizer.initialize() xong
        Log.i(TAG, "onCreate đã hoàn tất trên luồng Main.")

        enableEdgeToEdge()
        setContent {
            CardrecognitionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
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
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CardrecognitionTheme {
        Greeting("Android")
    }
}