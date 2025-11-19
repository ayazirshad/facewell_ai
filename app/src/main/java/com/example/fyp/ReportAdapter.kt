package com.example.fyp

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.models.Report
import java.text.SimpleDateFormat
import java.util.*

class ReportAdapter(
    private val items: List<Report>,
    private val onClick: (Report) -> Unit
) : RecyclerView.Adapter<ReportAdapter.Holder>() {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvSummaryShort: TextView = view.findViewById(R.id.tvSummaryShort)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvConfidence: TextView = view.findViewById(R.id.tvConfidence)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report_card, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        holder.tvType.text = r.type.capitalize(Locale.getDefault())
        holder.tvSummaryShort.text = if (r.summary.isNotBlank()) r.summary else "${r.leftLabel} / ${r.rightLabel}"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTxt = if (r.createdAt > 0) sdf.format(Date(r.createdAt)) else ""
        holder.tvDate.text = dateTxt
        holder.tvConfidence.text = "${(r.confidence * 100).toInt()}%"

        // try load preview
        try {
            if (r.previewUrl.isNotBlank()) holder.ivThumb.setImageURI(Uri.parse(r.previewUrl))
            else holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
        } catch (e: Exception) {
            holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
        }

        holder.itemView.setOnClickListener { onClick(r) }
    }

    override fun getItemCount(): Int = items.size
}
