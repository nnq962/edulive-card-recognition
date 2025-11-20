package com.example.card_recognition

object AppConfig {
    // Cấu hình cho Detector (Phát hiện đối tượng)
    const val USE_NNAPI_FOR_DETECTOR = false
    const val DETECTOR_THRESHOLD = 0.80f

    // Cấu hình cho Recognizer (Nhận diện thẻ)
    const val USE_NNAPI_FOR_RECOGNIZER = false
    const val RECOGNIZER_THRESHOLD = 0.80f
}
