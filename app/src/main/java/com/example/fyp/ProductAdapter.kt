package com.example.fyp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProductAdapter(
    private val context: Context,
    private val items: List<RecProduct>,
    private val onClick: (RecProduct) -> Unit
) : RecyclerView.Adapter<ProductAdapter.Holder>() {

    inner class Holder(view: View): RecyclerView.ViewHolder(view) {
        val iv: ImageView = view.findViewById(R.id.ivProductImage)
        val tvName: TextView = view.findViewById(R.id.tvProductName)
        val tvShort: TextView = view.findViewById(R.id.tvProductShort)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_product_card, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val product = items[position]      // <-- use a clear name, not `it`
        holder.tvName.text = product.name
        holder.tvShort.text = product.short

        // local drawable if provided (localImage contains drawable name without extension)
        if (!product.localImage.isNullOrEmpty()) {
            val resId = context.resources.getIdentifier(product.localImage, "drawable", context.packageName)
            if (resId != 0) {
                holder.iv.setImageResource(resId)
            } else {
                holder.iv.setImageResource(android.R.color.transparent)
            }
        } else {
            holder.iv.setImageResource(android.R.color.transparent)
        }

        holder.itemView.setOnClickListener { onClick(product) } // <-- fixed: pass product variable
    }

    override fun getItemCount(): Int = items.size
}
