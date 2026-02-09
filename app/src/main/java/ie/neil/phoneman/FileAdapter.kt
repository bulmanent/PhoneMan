package ie.neil.phoneman

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import ie.neil.phoneman.databinding.ItemFileBinding

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private val items = mutableListOf<FileItem>()
    private val selected = mutableSetOf<String>()

    fun submitList(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileItem> {
        return items.filter { selected.contains(it.file.uri.toString()) }
    }

    fun toggleSelection(item: FileItem) {
        val key = item.file.uri.toString()
        if (selected.contains(key)) selected.remove(key) else selected.add(key)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.file.uri.toString()) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FileViewHolder(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            binding.itemName.text = item.name
            binding.itemInfo.text = if (item.isDirectory) "Folder" else "File"
            binding.itemIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
            )

            val selectedColor = ContextCompat.getColor(binding.root.context, R.color.selected)
            val defaultColor = MaterialColors.getColor(binding.root, android.R.attr.colorBackground)
            binding.itemRoot.setBackgroundColor(
                if (selected.contains(item.file.uri.toString())) selectedColor else defaultColor
            )

            binding.root.setOnClickListener {
                onClick(item)
            }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }
}
