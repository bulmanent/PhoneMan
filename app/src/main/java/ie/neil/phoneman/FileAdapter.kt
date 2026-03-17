package ie.neil.phoneman

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Scale
import com.google.android.material.color.MaterialColors
import ie.neil.phoneman.databinding.ItemFileBinding
import ie.neil.phoneman.databinding.ItemFileCompactBinding
import ie.neil.phoneman.databinding.ItemFileGridBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif"
        )
    }

    private val items = mutableListOf<FileItem>()
    private val selected = mutableSetOf<String>()
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    var viewMode: ViewMode = ViewMode.DETAILS
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<FileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileItem> = items.filter { selected.contains(it.file.uri.toString()) }

    fun getSelectedCount(): Int = getSelected().size

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

    override fun getItemViewType(position: Int): Int = viewMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewMode.values()[viewType]) {
            ViewMode.COMPACT ->
                CompactViewHolder(ItemFileCompactBinding.inflate(inflater, parent, false))
            ViewMode.DETAILS ->
                DetailsViewHolder(ItemFileBinding.inflate(inflater, parent, false))
            ViewMode.ICONS ->
                GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false), iconSizeDp = 48)
            ViewMode.LARGE_ICONS ->
                GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false), iconSizeDp = 72)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is CompactViewHolder -> holder.bind(item)
            is DetailsViewHolder -> holder.bind(item)
            is GridViewHolder    -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun isSelected(item: FileItem) = selected.contains(item.file.uri.toString())

    private fun selectedBg(holder: RecyclerView.ViewHolder) =
        ContextCompat.getColor(holder.itemView.context, R.color.selected)

    private fun defaultBg(holder: RecyclerView.ViewHolder) =
        MaterialColors.getColor(holder.itemView, android.R.attr.colorBackground)

    inner class CompactViewHolder(private val binding: ItemFileCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.itemName.text = item.name
            binding.itemIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
            )
            binding.itemRoot.setBackgroundColor(if (isSelected(item)) selectedBg(this) else defaultBg(this))
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    inner class DetailsViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.itemName.text = item.name
            binding.itemIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
            )
            binding.itemInfo.text = if (item.isDirectory) {
                "Folder"
            } else {
                val size = Formatter.formatFileSize(binding.root.context, item.size)
                val date = dateFormat.format(Date(item.lastModified))
                "$size  •  $date"
            }
            binding.itemRoot.setBackgroundColor(if (isSelected(item)) selectedBg(this) else defaultBg(this))
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    inner class GridViewHolder(
        private val binding: ItemFileGridBinding,
        private val iconSizeDp: Int
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.itemName.text = item.name

            val density = binding.root.context.resources.displayMetrics.density
            val sizePx = (iconSizeDp * density).toInt()
            binding.itemIcon.layoutParams = binding.itemIcon.layoutParams.also {
                it.width = sizePx
                it.height = sizePx
            }

            val isImagePreview = !item.isDirectory && item.typeKey in IMAGE_EXTENSIONS
            if (isImagePreview) {
                binding.itemIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.itemIcon.load(item.file.uri) {
                    placeholder(R.drawable.ic_file)
                    error(R.drawable.ic_file)
                    crossfade(true)
                    scale(Scale.FILL)
                    size(sizePx, sizePx)
                }
            } else {
                binding.itemIcon.dispose()
                binding.itemIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.itemIcon.setImageResource(
                    if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
                )
            }

            binding.itemRoot.setBackgroundColor(if (isSelected(item)) selectedBg(this) else defaultBg(this))
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }
}
