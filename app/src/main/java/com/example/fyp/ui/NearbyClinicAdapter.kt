package com.example.fyp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.R
import com.example.fyp.models.Clinic
import com.example.fyp.utils.GeoUtils

class NearbyClinicAdapter(private val ctx: Context, private var items: List<Clinic>) :
    RecyclerView.Adapter<NearbyClinicAdapter.VH>() {

    fun update(list: List<Clinic>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clinic, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvClinicName)
        private val tvSpec: TextView = view.findViewById(R.id.tvClinicSpecialist)
        private val tvPhone: TextView = view.findViewById(R.id.tvClinicPhone)
        private val tvMeta: TextView = view.findViewById(R.id.tvClinicMeta)
        private val btnCall: ImageButton = view.findViewById(R.id.btnCall)
        private val btnMaps: Button = view.findViewById(R.id.btnOpenMaps)

        fun bind(c: Clinic) {
            tvName.text = c.name

            // Specialist / doctor name: prefer specialist, fallback to address or empty
            val specText = when {
                !c.specialist.isNullOrBlank() -> c.specialist!!
                !c.address.isNullOrBlank() -> c.address!!
                else -> ""
            }
            tvSpec.text = specText
            tvSpec.visibility = if (specText.isBlank()) View.GONE else View.VISIBLE

            // Phone handling
            if (!c.phone.isNullOrBlank()) {
                tvPhone.visibility = View.VISIBLE
                tvPhone.text = c.phone
                btnCall.visibility = View.VISIBLE
                btnCall.setOnClickListener {
                    val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))
                    ctx.startActivity(i)
                }
                // also make phone text clickable -> open dialer
                tvPhone.setOnClickListener {
                    val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))
                    ctx.startActivity(i)
                }
            } else {
                tvPhone.visibility = View.GONE
                btnCall.visibility = View.GONE
            }

            // distance & ETA
            val dist = c.distanceMeters?.let { GeoUtils.formatMetersToKmString(it) } ?: "--"
            val eta = GeoUtils.secondsToMinStr(c.travelTimeSecCar)
            tvMeta.text = "$dist • ETA: $eta"

            // open maps button
            btnMaps.setOnClickListener {
                // try to open Google Maps navigation; fallback to geo uri if not available
                val gmmIntentUri = Uri.parse("google.navigation:q=${c.lat},${c.lng}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(mapIntent)
                } else {
                    val geo = Uri.parse("geo:${c.lat},${c.lng}?q=${Uri.encode(c.name)}")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, geo))
                }
            }
        }
    }
}
