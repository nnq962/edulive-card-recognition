package com.example.card_recognition

object AppConfig {
    // Cấu hình cho Detector (Phát hiện đối tượng)
    val USE_NNAPI_FOR_DETECTOR get() = false
    const val DETECTOR_THRESHOLD = 0.80f

    // Cấu hình cho Recognizer (Nhận diện thẻ)
    val USE_NNAPI_FOR_RECOGNIZER get() = false
    const val RECOGNIZER_THRESHOLD = 0.80f
}