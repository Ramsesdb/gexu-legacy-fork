package tachiyomi.domain.ai.service

import kotlin.math.ln

/**
 * BM25 (Best Matching 25) re-ranker for improving RAG search results.
 *
 * After initial vector search returns candidates, this re-ranker uses
 * term frequency analysis to improve precision.
 *
 * BM25 is particularly good at matching exact terms that vector search might miss.
 */
class Bm25Reranker(
    private val k1: Double = 1.2,  // Term frequency saturation
    private val b: Double = 0.75,  // Length normalization
) {

    /**
     * Re-rank documents based on BM25 score.
     *
     * @param query The search query
     * @param documents Map of document ID to document text
     * @param limit Maximum number of results to return
     * @return List of document IDs sorted by relevance (best first)
     */
    fun rerank(
        query: String,
        documents: Map<Long, String>,
        limit: Int = 5
    ): List<Long> {
        if (documents.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return documents.keys.take(limit)

        // Calculate corpus statistics
        val avgDocLength = documents.values.map { tokenize(it).size }.average()
        val docCount = documents.size

        // Calculate IDF for each query term
        val idfScores = queryTerms.associateWith { term ->
            val docsContainingTerm = documents.count { (_, text) ->
                tokenize(text).contains(term)
            }
            calculateIdf(docCount, docsContainingTerm)
        }

        // Score each document
        val scores = documents.map { (docId, text) ->
            val docTerms = tokenize(text)
            val docLength = docTerms.size

            var score = 0.0
            for (term in queryTerms) {
                val tf = docTerms.count { it == term }
                val idf = idfScores[term] ?: 0.0

                // BM25 formula
                val numerator = tf * (k1 + 1)
                val denominator = tf + k1 * (1 - b + b * (docLength / avgDocLength))
                score += idf * (numerator / denominator)
            }

            docId to score
        }

        // Sort by score and return top results
        return scores
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Hybrid re-ranking combining vector similarity and BM25 scores.
     *
     * @param query The search query
     * @param documents Map of document ID to document text
     * @param vectorRanking Original vector search ranking (docId to rank position)
     * @param vectorWeight Weight for vector ranking (0.0 to 1.0)
     * @param limit Maximum number of results
     * @return List of document IDs sorted by combined score
     */
    fun hybridRerank(
        query: String,
        documents: Map<Long, String>,
        vectorRanking: List<Long>,
        vectorWeight: Double = 0.7,
        limit: Int = 5
    ): List<Long> {
        if (documents.isEmpty()) return emptyList()

        val bm25Weight = 1.0 - vectorWeight

        // Get BM25 scores
        val queryTerms = tokenize(query)
        val avgDocLength = documents.values.map { tokenize(it).size }.average()
        val docCount = documents.size

        val idfScores = queryTerms.associateWith { term ->
            val docsContainingTerm = documents.count { (_, text) ->
                tokenize(text).contains(term)
            }
            calculateIdf(docCount, docsContainingTerm)
        }

        val bm25Scores = documents.map { (docId, text) ->
            val docTerms = tokenize(text)
            val docLength = docTerms.size

            var score = 0.0
            for (term in queryTerms) {
                val tf = docTerms.count { it == term }
                val idf = idfScores[term] ?: 0.0
                val numerator = tf * (k1 + 1)
                val denominator = tf + k1 * (1 - b + b * (docLength / avgDocLength))
                score += idf * (numerator / denominator)
            }
            docId to score
        }.toMap()

        // Normalize BM25 scores to 0-1 range
        val maxBm25 = bm25Scores.values.maxOrNull() ?: 1.0
        val normalizedBm25 = if (maxBm25 > 0) {
            bm25Scores.mapValues { it.value / maxBm25 }
        } else {
            bm25Scores.mapValues { 0.0 }
        }

        // Convert vector ranking to scores (higher rank = higher score)
        val vectorScores = vectorRanking.mapIndexed { index, docId ->
            val score = 1.0 - (index.toDouble() / vectorRanking.size.coerceAtLeast(1))
            docId to score
        }.toMap()

        // Combine scores
        val combinedScores = documents.keys.map { docId ->
            val vectorScore = vectorScores[docId] ?: 0.0
            val bm25Score = normalizedBm25[docId] ?: 0.0
            val combined = (vectorWeight * vectorScore) + (bm25Weight * bm25Score)
            docId to combined
        }

        return combinedScores
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Tokenize text into terms.
     * Uses Unicode-aware splitting to support Japanese, Korean, Chinese,
     * and accented characters (Spanish, French, etc.)
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))  // Unicode-aware: split on whitespace and punctuation
            .filter { it.length > 1 }
    }

    /**
     * Calculate Inverse Document Frequency.
     */
    private fun calculateIdf(totalDocs: Int, docsWithTerm: Int): Double {
        if (docsWithTerm == 0) return 0.0
        return ln((totalDocs - docsWithTerm + 0.5) / (docsWithTerm + 0.5) + 1.0)
    }
}
