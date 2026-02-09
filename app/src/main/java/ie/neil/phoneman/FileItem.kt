package ie.neil.phoneman

import androidx.documentfile.provider.DocumentFile

 data class FileItem(
    val file: DocumentFile,
    val isDirectory: Boolean,
    val name: String
)
