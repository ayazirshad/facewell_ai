package com.example.fyp

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.models.Report
import java.text.SimpleDateFormat
import java.util.Locale

class ReportAdapter(
    private var items: List<Report>,
    private val onItemClick: (Report) -> Unit,
    private val onDeleteClick: ((Report) -> Unit)? = null
) : RecyclerView.Adapter<ReportAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        val tvType: TextView = itemView.findViewById(R.id.tvType)
        val tvSummaryShort: TextView = itemView.findViewById(R.id.tvSummaryShort)
        val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        val tvConfidence: TextView = itemView.findViewById(R.id.tvConfidence)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteReport)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]

        holder.tvType.text = r.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        // summary fallback
        val summary = when {
            !r.summary.isNullOrBlank() -> r.summary!!
            r.eye_scan != null && !r.eye_scan.summary.isNullOrBlank() -> r.eye_scan.summary!!
            r.skin_scan != null && !r.skin_scan.summary.isNullOrBlank() -> r.skin_scan.summary!!
            r.mood_scan != null && !r.mood_scan.summary.isNullOrBlank() -> r.mood_scan.summary!!
            else -> ""
        }
        holder.tvSummaryShort.text = summary

        // createdAt -> yyyy-MM-dd
        try {
            val ts = r.createdAt
            if (ts != null) {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                holder.tvDate.text = sdf.format(ts.toDate())
            } else {
                holder.tvDate.text = ""
            }
        } catch (e: Exception) {
            holder.tvDate.text = ""
        }

        // accuracy (0..1)
        val acc = try { r.accuracy } catch (_: Exception) { 0.0 }
        holder.tvConfidence.text = "${(acc * 100).toInt()}%"

        // show placeholder icon if no imageUrl
        try {
            val url = r.imageUrl
            if (!url.isNullOrBlank()) {
                // still simple setImageURI for now (no image libs). If url is remote, consider Glide/Coil.
                holder.ivThumb.setImageURI(Uri.parse(url))
            } else {
                holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
            }
        } catch (_: Exception) {
            holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
        }

        holder.itemView.setOnClickListener { onItemClick(r) }

        if (onDeleteClick != null) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener { onDeleteClick.invoke(r) }
        } else {
            holder.btnDelete.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    fun swapData(newItems: List<Report>) {
        items = newItems
        notifyDataSetChanged()
    }
}
