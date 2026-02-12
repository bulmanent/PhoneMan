package ie.neil.phoneman

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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
    companion object {
        private const val PREFS_NAME = "phoneman"
        private const val KEY_ROOT_URI = "root_uri"
    }

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
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.error_permission_not_persisted, Toast.LENGTH_SHORT).show()
            }
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

        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ROOT_URI, null)
        val persistedRoots = getPersistedTreeUris()
        val initialRoot = stored?.let(Uri::parse)?.takeIf { uri ->
            persistedRoots.any { it == uri }
        } ?: persistedRoots.firstOrNull()

        if (initialRoot == null) {
            requestRoot()
        } else {
            saveRoot(initialRoot)
            openRoot(initialRoot)
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
                showRootChooser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestRoot(initialUri: Uri? = rootUri) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        pickRoot.launch(intent)
    }

    private fun saveRoot(uri: Uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ROOT_URI, uri.toString())
            .apply()
    }

    private fun showRootChooser() {
        val roots = getPersistedTreeUris()
        if (roots.isEmpty()) {
            requestRoot()
            return
        }

        val labels = roots.map { uri ->
            DocumentFile.fromTreeUri(this, uri)?.name
                ?: uri.lastPathSegment
                ?: getString(R.string.path_unknown)
        } + getString(R.string.action_add_folder)

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_folder)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == roots.size) {
                    requestRoot()
                } else {
                    val selected = roots[which]
                    saveRoot(selected)
                    openRoot(selected)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getPersistedTreeUris(): List<Uri> {
        return contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri }
            .filter { DocumentsContract.isTreeUri(it) }
            .toList()
    }

    private fun openRoot(uri: Uri) {
        rootUri = uri
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.canRead()) {
            Toast.makeText(this, R.string.error_folder_access_lost, Toast.LENGTH_SHORT).show()
            requestRoot(uri)
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
