package com.example.fyp

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class RecProduct(
    val id: String,
    val name: String,
    val short: String,
    val localImage: String? = null,
    val imageUrl: String? = null
)

data class Recommendation(
    val id: String,
    val title: String,
    val summary: String,
    val tips: List<String>,
    val products: List<RecProduct>
)

object RecommendationProvider {
    private var eyeMap: Map<String, Recommendation>? = null
    private var skinMap: Map<String, Recommendation>? = null

    /**
     * Load recommendations from assets.
     * Backwards-compatible:
     *  - If filename contains "eye" -> loads eye map only (old behavior).
     *  - If filename contains "skin" -> loads skin map only.
     *  - If filename is omitted or equals "both", attempt to load both defaults.
     */
    fun loadFromAssets(context: Context, filename: String? = null) {
        try {
            if (filename.isNullOrBlank() || filename == "both") {
                // attempt load both
                if (eyeMap == null) eyeMap = loadMap(context, "eye_recommendations.json", "eye")
                if (skinMap == null) skinMap = loadMap(context, "skin_recommendations.json", "skin")
                return
            }
            val lower = filename.lowercase()
            when {
                "eye" in lower -> { if (eyeMap == null) eyeMap = loadMap(context, filename, "eye") }
                "skin" in lower -> { if (skinMap == null) skinMap = loadMap(context, filename, "skin") }
                else -> { // fallback: attempt to load the provided file as either
                    val m = loadMap(context, filename, "eye")
                    if (m.isNotEmpty()) eyeMap = m
                    val m2 = loadMap(context, filename, "skin")
                    if (m2.isNotEmpty()) skinMap = m2
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (eyeMap == null) eyeMap = mapOf()
            if (skinMap == null) skinMap = mapOf()
        }
    }

    private fun loadMap(context: Context, filename: String, rootKey: String): Map<String, Recommendation> {
        return try {
            val stream = context.assets.open(filename)
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
            reader.close()
            val root = JSONObject(sb.toString())
            if (!root.has(rootKey)) return mapOf()
            val node = root.getJSONObject(rootKey)
            val keys = node.keys()
            val map = mutableMapOf<String, Recommendation>()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = node.getJSONObject(k)
                val title = obj.optString("title", k)
                val summary = obj.optString("summary", "")
                val tipsArr = obj.optJSONArray("tips")
                val tips = mutableListOf<String>()
                if (tipsArr != null) {
                    for (i in 0 until tipsArr.length()) tips.add(tipsArr.getString(i))
                }
                val prods = mutableListOf<RecProduct>()
                val prodsArr = obj.optJSONArray("products")
                if (prodsArr != null) {
                    for (i in 0 until prodsArr.length()) {
                        val p = prodsArr.getJSONObject(i)
                        prods.add(
                            RecProduct(
                                id = p.optString("id"),
                                name = p.optString("name"),
                                short = p.optString("short"),
                                localImage = p.optString("localImage", null),
                                imageUrl = p.optString("imageUrl", null)
                            )
                        )
                    }
                }
                map[k] = Recommendation(k, title, summary, tips, prods)
            }
            map.toMap()
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf()
        }
    }

    // Old eye getter (keeps compatibility)
    fun getEyeRecommendation(key: String): Recommendation? {
        return eyeMap?.get(key)
    }

    // New skin getter
    fun getSkinRecommendation(key: String): Recommendation? {
        return skinMap?.get(key)
    }
}
