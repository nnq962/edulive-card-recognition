package com.example.card_recognition

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Data class để load và quản lý reference embeddings từ data.json
 * Format: { "category1": [[emb1], [emb2], ...], "category2": [...], ... }
 */
data class EmbeddingData(
    val categoryEmbeddings: Map<String, List<FloatArray>>
) {
    companion object {
        private const val TAG = "EmbeddingData"
        private const val DEFAULT_EMBEDDING_SIZE = 1280
        const val DEFAULT_THRESHOLD = 0.75f
        
        fun loadFromAssets(context: Context, fileName: String = "data.json"): EmbeddingData? {
            return try {
                val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonString)

                val categoryEmbeddings = mutableMapOf<String, List<FloatArray>>()
                var totalEmbeddings = 0

                // Parse JSON: { "category": [[emb1], [emb2], ...] }
                jsonObject.keys().forEach { category ->
                    val embeddingsArray = jsonObject.getJSONArray(category)
                    val embeddings = mutableListOf<FloatArray>()

                    for (i in 0 until embeddingsArray.length()) {
                        val embArray = embeddingsArray.getJSONArray(i)
                        val embedding = FloatArray(embArray.length())

                        for (j in 0 until embArray.length()) {
                            embedding[j] = embArray.getDouble(j).toFloat()
                        }

                        // Validate embedding size
                        if (embedding.size != DEFAULT_EMBEDDING_SIZE) {
                            Log.w(TAG, "Warning: Embedding size ${embedding.size} != expected $DEFAULT_EMBEDDING_SIZE for category '$category'")
                        }

                        embeddings.add(embedding)
                        totalEmbeddings++
                    }

                    if (embeddings.isNotEmpty()) {
                        categoryEmbeddings[category] = embeddings
                    }
                }

                if (categoryEmbeddings.isEmpty()) {
                    Log.e(TAG, "No valid embeddings found in $fileName")
                    return null
                }

                Log.i(TAG, "Loaded ${categoryEmbeddings.size} categories, $totalEmbeddings total embeddings")
                categoryEmbeddings.forEach { (category, embeddings) ->
                    Log.d(TAG, "  $category: ${embeddings.size} embeddings")
                }

                EmbeddingData(categoryEmbeddings)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading embeddings from $fileName", e)
                null
            }
        }
    }

    /**
     * Result class cho findBestMatch
     */
    data class MatchResult(
        val category: String?,
        val similarity: Float,
        val isMatch: Boolean
    )

    /**
     * Tìm category có cosine similarity cao nhất với query embedding
     * 
     * @param queryEmbedding Query embedding vector
     * @param threshold Ngưỡng để coi là match (default: 0.75)
     * @param earlyStopThreshold Nếu tìm được similarity > threshold này thì dừng sớm (default: 0.99)
     * @return MatchResult với category, similarity, và isMatch flag
     */
    fun findBestMatch(
        queryEmbedding: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD,
        earlyStopThreshold: Float = 0.99f
    ): MatchResult {
        // Validate input
        if (queryEmbedding.size != DEFAULT_EMBEDDING_SIZE) {
            Log.w(TAG, "Query embedding size ${queryEmbedding.size} != expected $DEFAULT_EMBEDDING_SIZE")
        }

        if (categoryEmbeddings.isEmpty()) {
            Log.w(TAG, "No reference embeddings loaded")
            return MatchResult(null, 0f, false)
        }

        var bestCategory: String? = null
        var bestSimilarity = 0f
        var comparisonCount = 0

        // Iterate through all categories and embeddings
        run earlyStop@ {
            categoryEmbeddings.forEach { (category, embeddings) ->
                embeddings.forEach { refEmbedding ->
                    comparisonCount++
                    val similarity = cosineSimilarity(queryEmbedding, refEmbedding)
                    
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        bestCategory = category
                        
                        // Early stopping if found excellent match
                        if (similarity >= earlyStopThreshold) {
                            Log.d(TAG, "Early stop: found excellent match (similarity=$similarity)")
                            return@earlyStop
                        }
                    }
                }
            }
        }

        val isMatch = bestSimilarity >= threshold
        
        Log.d(TAG, "findBestMatch: $comparisonCount comparisons, best=$bestCategory, similarity=$bestSimilarity, match=$isMatch")

        return MatchResult(
            category = if (isMatch) bestCategory else null,
            similarity = bestSimilarity,
            isMatch = isMatch
        )
    }

    /**
     * Tính cosine similarity giữa 2 embeddings
     */
    private fun cosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        if (emb1.size != emb2.size) {
            Log.w(TAG, "Embedding size mismatch: ${emb1.size} vs ${emb2.size}")
            return 0f
        }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
            norm1 += emb1[i] * emb1[i]
            norm2 += emb2[i] * emb2[i]
        }

        norm1 = kotlin.math.sqrt(norm1)
        norm2 = kotlin.math.sqrt(norm2)

        if (norm1 == 0f || norm2 == 0f) {
            Log.w(TAG, "Zero norm detected in cosine similarity")
            return 0f
        }

        return dotProduct / (norm1 * norm2)
    }
    
    /**
     * Kiểm tra xem data đã được load chưa
     */
    fun isLoaded(): Boolean = categoryEmbeddings.isNotEmpty()
    
    /**
     * Lấy số lượng categories
     */
    fun getCategoryCount(): Int = categoryEmbeddings.size
    
    /**
     * Lấy tổng số embeddings
     */
    fun getTotalEmbeddingCount(): Int = categoryEmbeddings.values.sumOf { it.size }
}
