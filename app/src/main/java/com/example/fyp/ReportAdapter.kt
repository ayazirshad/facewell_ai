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
        holder.tvType.text = r.type.capitalize()
        holder.tvSummaryShort.text = if (r.summary.isNotEmpty()) r.summary else "${r.leftLabel} / ${r.rightLabel}"
        if (r.createdAt > 0) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                holder.tvDate.text = sdf.format(java.util.Date(r.createdAt))
            } catch (_: Exception) { holder.tvDate.text = "" }
        } else holder.tvDate.text = ""

        holder.tvConfidence.text = "${(r.confidence * 100).toInt()}%"

        // preview if available
        try {
            if (r.previewUrl.isNotBlank()) holder.ivThumb.setImageURI(Uri.parse(r.previewUrl))
            else holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
        } catch (_: Exception) {
            holder.ivThumb.setImageResource(R.drawable.ic_report_placeholder)
        }

        holder.itemView.setOnClickListener { onItemClick(r) }

        // delete button
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
