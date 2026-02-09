package ie.neil.phoneman

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import ie.neil.phoneman.databinding.ActivityMainBinding
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter

    private var rootUri: Uri? = null
    private val dirStack = ArrayDeque<DocumentFile>()
    private var actionMode: ActionMode? = null

    private val clipboard = mutableListOf<DocumentFile>()
    private var clipboardCut = false

    private val pickRoot = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val uri = it.data?.data ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            saveRoot(uri)
            openRoot(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = FileAdapter(
            onClick = { item ->
                if (actionMode != null) {
                    adapter.toggleSelection(item)
                    updateActionModeTitle()
                } else {
                    if (item.isDirectory) {
                        openDirectory(item.file)
                    } else {
                        Toast.makeText(this, item.name, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLongClick = { item ->
                if (actionMode == null) {
                    actionMode = startActionMode(actionModeCallback)
                }
                adapter.toggleSelection(item)
                updateActionModeTitle()
            }
        )

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        val stored = getSharedPreferences("phoneman", MODE_PRIVATE).getString("root_uri", null)
        if (stored == null) {
            requestRoot()
        } else {
            openRoot(Uri.parse(stored))
        }
    }

    override fun onBackPressed() {
        if (actionMode != null) {
            actionMode?.finish()
            return
        }
        if (dirStack.size > 1) {
            dirStack.removeLast()
            loadCurrent()
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_paste)?.isVisible = clipboard.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadCurrent()
                true
            }
            R.id.action_new_folder -> {
                promptNewFolder()
                true
            }
            R.id.action_paste -> {
                pasteClipboard()
                true
            }
            R.id.action_up -> {
                requestRoot()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestRoot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        pickRoot.launch(intent)
    }

    private fun saveRoot(uri: Uri) {
        getSharedPreferences("phoneman", MODE_PRIVATE)
            .edit()
            .putString("root_uri", uri.toString())
            .apply()
    }

    private fun openRoot(uri: Uri) {
        rootUri = uri
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            requestRoot()
            return
        }
        dirStack.clear()
        dirStack.add(root)
        loadCurrent()
    }

    private fun openDirectory(dir: DocumentFile) {
        dirStack.add(dir)
        loadCurrent()
    }

    private fun loadCurrent() {
        val dir = dirStack.lastOrNull() ?: return
        val children = dir.listFiles()
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                FileItem(file, file.isDirectory, name)
            }
            .sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })

        adapter.submitList(children)
        binding.emptyState.visibility = if (children.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.pathText.text = buildPath()
        invalidateOptionsMenu()
    }

    private fun buildPath(): String {
        if (dirStack.isEmpty()) return getString(R.string.path_unknown)
        return dirStack.joinToString(" / ") { it.name ?: getString(R.string.path_unknown) }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menuInflater.inflate(R.menu.selection_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selected = adapter.getSelected()
            if (selected.isEmpty()) return true
            return when (item.itemId) {
                R.id.action_select_all -> {
                    adapter.selectAll()
                    updateActionModeTitle()
                    true
                }
                R.id.action_copy -> {
                    clipboard.clear()
                    clipboard.addAll(selected.map { it.file })
                    clipboardCut = false
                    mode.finish()
                    true
                }
                R.id.action_cut -> {
                    clipboard.clear()
                    clipboard.addAll(selected.map { it.file })
                    clipboardCut = true
                    mode.finish()
                    true
                }
                R.id.action_rename -> {
                    if (selected.size == 1) {
                        promptRename(selected.first().file)
                    }
                    mode.finish()
                    true
                }
                R.id.action_delete -> {
                    confirmDelete(selected.map { it.file })
                    mode.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    private fun updateActionModeTitle() {
        actionMode?.title = "${adapter.getSelected().size} selected"
    }

    private fun confirmDelete(files: List<DocumentFile>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.confirm) { _, _ ->
                files.forEach { it.delete() }
                loadCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRename(file: DocumentFile) {
        val input = EditText(this)
        input.setText(file.name ?: "")
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    file.renameTo(name)
                    loadCurrent()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptNewFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_folder_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    dirStack.lastOrNull()?.createDirectory(name)
                    loadCurrent()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pasteClipboard() {
        val targetDir = dirStack.lastOrNull() ?: return
        clipboard.forEach { source ->
            if (source.isDirectory) {
                copyDirectory(source, targetDir)
            } else {
                copyFile(source, targetDir)
            }
        }
        if (clipboardCut) {
            clipboard.forEach { it.delete() }
            clipboard.clear()
            clipboardCut = false
        }
        loadCurrent()
    }

    private fun copyDirectory(source: DocumentFile, target: DocumentFile) {
        val newDir = target.createDirectory(source.name ?: "Folder") ?: return
        source.listFiles().forEach { child ->
            if (child.isDirectory) {
                copyDirectory(child, newDir)
            } else {
                copyFile(child, newDir)
            }
        }
    }

    private fun copyFile(source: DocumentFile, targetDir: DocumentFile) {
        val name = source.name ?: return
        val type = source.type ?: "application/octet-stream"
        val dest = targetDir.createFile(type, name) ?: return
        val input: InputStream = contentResolver.openInputStream(source.uri) ?: return
        val output: OutputStream = contentResolver.openOutputStream(dest.uri) ?: return
        input.use { inp ->
            output.use { out ->
                inp.copyTo(out)
            }
        }
    }
}
