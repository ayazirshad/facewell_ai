package com.example.fyp.providers

import android.util.Log
import com.example.fyp.models.Clinic
import com.example.fyp.utils.GeoUtils
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt

private const val TAG = "OsmClinicProvider"
private val JSON = MediaType.get("application/json; charset=utf-8")

class OsmClinicProvider : ClinicProvider {

    private val overpassBase = "https://overpass-api.de/api/interpreter"
    private val osrmBase = "https://router.project-osrm.org/route/v1"

    private val client = OkHttpClient()
    private val gson = Gson()

    override fun searchClinics(lat: Double, lng: Double, radiusMeters: Int, category: ClinicCategory, cb: (List<Clinic>?) -> Unit) {
        // Build category-specific extra *clauses* (SEPARATE clauses — NOT appended to existing ones)
        val categoryClauses = when (category) {
            ClinicCategory.EYE -> listOf(
                // look for healthcare tags mentioning optician/ophthalmology/eye
                "node(around:$radiusMeters,$lat,$lng)[healthcare~\"optician|ophthalmology|eye|ophthalmologist\"];",
                "way(around:$radiusMeters,$lat,$lng)[healthcare~\"optician|ophthalmology|eye|ophthalmologist\"];"
            )
            ClinicCategory.SKIN -> listOf(
                "node(around:$radiusMeters,$lat,$lng)[healthcare~\"dermatology|skin|dermatologist\"];",
                "way(around:$radiusMeters,$lat,$lng)[healthcare~\"dermatology|skin|dermatologist\"];"
            )
            ClinicCategory.MOOD -> listOf(
                "node(around:$radiusMeters,$lat,$lng)[healthcare~\"psychiatrist|psychology|mental|psychologist\"];",
                "way(around:$radiusMeters,$lat,$lng)[healthcare~\"psychiatrist|psychology|mental|psychologist\"];"
            )
        }

        // Core clauses (mirrors your browser-working query)
        val coreClauses = listOf(
            "node(around:$radiusMeters,$lat,$lng)[amenity=clinic];",
            "node(around:$radiusMeters,$lat,$lng)[amenity=hospital];",
            "node(around:$radiusMeters,$lat,$lng)[amenity=doctors];",
            "node(around:$radiusMeters,$lat,$lng)[healthcare=clinic];",
            "node(around:$radiusMeters,$lat,$lng)[healthcare=doctor];",
            "way(around:$radiusMeters,$lat,$lng)[amenity=clinic];",
            "way(around:$radiusMeters,$lat,$lng)[amenity=hospital];",
            "relation(around:$radiusMeters,$lat,$lng)[amenity=clinic];"
        )

        // Combine clauses: core + category-specific (all separate clauses inside the (...) group)
        val allClauses = (coreClauses + categoryClauses).joinToString(separator = "\n  ")

        val rawQuery = """
            [out:json][timeout:25];
            (
              $allClauses
            );
            out center tags;
        """.trimIndent()

        // Encode as GET ?data=... (matches browser)
        val encoded = try { URLEncoder.encode(rawQuery, "UTF-8") } catch (e: Exception) { rawQuery }
        val url = "$overpassBase?data=$encoded"

        Log.d(TAG, "Overpass GET URL (preview): ${url.take(400)}")

        val req = Request.Builder().url(url).get().build()

        try {
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Overpass call failed: ${e.message}", e)
                    cb(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val rc = try { resp.code() } catch (_: Throwable) { -1 }
                        val bodyTxt = try { resp.body()?.string() } catch (e: Exception) {
                            Log.e(TAG, "Error reading Overpass response body", e)
                            null
                        }

                        Log.d(TAG, "Overpass rc=$rc bodyLen=${bodyTxt?.length ?: 0}")
                        if (bodyTxt != null) Log.d(TAG, "Overpass body preview: ${bodyTxt.take(2000)}")

                        if (rc !in 200..299 || bodyTxt == null) {
                            Log.e(TAG, "Overpass failed rc=$rc bodyPreview=${bodyTxt?.take(300)}")
                            cb(null)
                            return
                        }

                        try {
                            val over = gson.fromJson(bodyTxt, OverpassResponse::class.java)
                            val elems = over.elements ?: emptyList()
                            if (elems.isEmpty()) {
                                Log.d(TAG, "Overpass returned zero elements")
                                cb(emptyList())
                                return
                            }

                            val clinics = elems.mapNotNull { el ->
                                val (plat, plng) = when {
                                    el.type == "node" && el.lat != null && el.lon != null -> Pair(el.lat, el.lon)
                                    el.center != null -> Pair(el.center.lat, el.center.lon)
                                    else -> Pair(null, null)
                                }
                                val latv = plat ?: return@mapNotNull null
                                val lngv = plng ?: return@mapNotNull null

                                val tags = el.tags
                                val name = tags?.get("name") ?: tags?.get("operator") ?: "Clinic"
                                val phone = tags?.get("phone") ?: tags?.get("contact:phone") ?: tags?.get("telephone")
                                val email = tags?.get("email") ?: tags?.get("contact:email")
                                val specialty = tags?.get("speciality") ?: tags?.get("healthcare")
                                val dist = GeoUtils.haversineMeters(lat, lng, latv, lngv)
                                Clinic(
                                    id = el.id.toString(),
                                    name = name,
                                    lat = latv,
                                    lng = lngv,
                                    address = tags?.get("addr:full") ?: tags?.get("addr:street"),
                                    phone = phone,
                                    email = email,
                                    specialist = specialty,
                                    distanceMeters = dist.roundToInt(),
                                    travelTimeSecCar = null,
                                    travelDistanceMeters = null,
                                    rating = null,
                                    openNow = null
                                )
                            }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }

                            // If no coords-bearing results, return empty list
                            if (clinics.isEmpty()) {
                                Log.d(TAG, "Parsed zero clinics after filtering coords/tags")
                                cb(emptyList())
                                return
                            }

                            // Enrich top N with OSRM travel time (best-effort)
                            val top = clinics.take(6)
                            var remaining = top.size
                            val enriched = clinics.toMutableList()

                            top.forEach { c ->
                                val urlOsrm = "${osrmBase}/driving/${lng},${lat};${c.lng},${c.lat}?overview=false"
                                val r = Request.Builder().url(urlOsrm).get().build()
                                client.newCall(r).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        Log.w(TAG, "OSRM fail: ${e.message}")
                                        remaining -= 1
                                        if (remaining <= 0) cb(enriched)
                                    }

                                    override fun onResponse(call: Call, response: Response) {
                                        response.use { rr ->
                                            val rc2 = try { rr.code() } catch (_: Throwable) { -1 }
                                            val bodyTxt2 = try { rr.body()?.string() } catch (_: Exception) { null }
                                            if (rc2 in 200..299 && bodyTxt2 != null) {
                                                try {
                                                    val or = gson.fromJson(bodyTxt2, OsrmRouteResponse::class.java)
                                                    val dur = or.routes?.firstOrNull()?.legs?.firstOrNull()?.duration ?: or.routes?.firstOrNull()?.duration
                                                    val dist = or.routes?.firstOrNull()?.legs?.firstOrNull()?.distance ?: or.routes?.firstOrNull()?.distance
                                                    val idx = enriched.indexOfFirst { it.id == c.id }
                                                    if (idx >= 0) {
                                                        val old = enriched[idx]
                                                        enriched[idx] = old.copy(
                                                            travelTimeSecCar = dur?.toInt(),
                                                            travelDistanceMeters = dist?.toInt()
                                                        )
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "OSRM parse err", e)
                                                }
                                            } else {
                                                Log.w(TAG, "OSRM bad response rc=$rc2 bodyPreview=${bodyTxt2?.take(300)}")
                                            }
                                            remaining -= 1
                                            if (remaining <= 0) cb(enriched)
                                        }
                                    }
                                })
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Overpass parse error", e)
                            cb(null)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Overpass request exception", e)
            cb(null)
        }
    }

    // minimal models for parsing
    data class OverpassResponse(val elements: List<Element>?)
    data class Element(val type: String?, val id: Long, val lat: Double?, val lon: Double?, val center: Center?, val tags: Map<String, String>?)
    data class Center(val lat: Double, val lon: Double)
    data class OsrmRouteResponse(val routes: List<Route>?)
    data class Route(val legs: List<Leg>?, val duration: Double?, val distance: Double?)
    data class Leg(val duration: Double?, val distance: Double?)
}
