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

    // Load from assets; call once (fast)
    fun loadFromAssets(context: Context, filename: String = "eye_recommendations.json") {
        if (eyeMap != null) return
        try {
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
            val eye = root.getJSONObject("eye")
            val keys = eye.keys()
            val map = mutableMapOf<String, Recommendation>()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = eye.getJSONObject(k)
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
            eyeMap = map.toMap()
        } catch (e: Exception) {
            e.printStackTrace()
            eyeMap = mapOf()
        }
    }

    fun getEyeRecommendation(key: String): Recommendation? {
        return eyeMap?.get(key)
    }
}
