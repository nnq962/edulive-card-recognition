package com.example.card_recognition

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
// Thêm các import này
import androidx.lifecycle.lifecycleScope
import com.example.card_recognition.ui.theme.CardrecognitionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


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
                recognizer.initialize(this@MainActivity, useNNAPI = false)
                Log.i(TAG, "Recognizer đã khởi tạo thành công.")

                // Giờ bạn có thể sử dụng recognizer để dự đoán
                // (Lưu ý: nếu bạn cần cập nhật UI từ đây,
                // bạn phải dùng withContext(Dispatchers.Main) { ... })

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