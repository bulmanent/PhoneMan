package ie.neil.phoneman

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ie.neil.phoneman.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "phoneman"
        private const val KEY_ROOT_URI = "root_uri"

        private const val TRANSFER_NOTIFICATION_CHANNEL_ID = "file_transfer"
        private const val TRANSFER_NOTIFICATION_ID = 1001
        private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    }

    private data class TransferTotals(
        val totalFiles: Int,
        val totalBytes: Long
    )

    private data class DeleteTotals(
        val totalItems: Int,
        val totalBytes: Long
    )

    private data class TransferProgress(
        val totalFiles: Int,
        val totalBytes: Long,
        var copiedFiles: Int = 0,
        var copiedBytes: Long = 0,
        var currentName: String = ""
    )

    private data class TransferResult(
        val failedFiles: Int,
        val deletedAfterCutFailures: Int
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter

    private var rootUri: Uri? = null
    private val dirStack = ArrayDeque<DocumentFile>()
    private var actionMode: ActionMode? = null

    private val clipboard = mutableListOf<DocumentFile>()
    private var clipboardCut = false

    private var transferInProgress = false
    private var canShowNotifications = false

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
        createTransferNotificationChannel()

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
        menu.findItem(R.id.action_paste)?.let {
            it.isVisible = clipboard.isNotEmpty()
            it.isEnabled = !transferInProgress
        }
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
        binding.emptyState.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
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
            if (transferInProgress) {
                Toast.makeText(this@MainActivity, R.string.transfer_already_running, Toast.LENGTH_SHORT).show()
                return true
            }

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
                deleteWithProgress(files)
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
        if (transferInProgress) {
            Toast.makeText(this, R.string.transfer_already_running, Toast.LENGTH_SHORT).show()
            return
        }

        val targetDir = dirStack.lastOrNull() ?: return
        if (clipboard.isEmpty()) return

        val sources = clipboard.toList()
        val isCut = clipboardCut
        val operationLabel = if (isCut) {
            getString(R.string.transfer_moving)
        } else {
            getString(R.string.transfer_copying)
        }

        transferInProgress = true
        invalidateOptionsMenu()

        showTransferProgress(
            copiedFiles = 0,
            totalFiles = 0,
            copiedBytes = 0,
            totalBytes = 0,
            currentName = "",
            operationLabel = operationLabel,
            indeterminate = true
        )

        lifecycleScope.launch {
            canShowNotifications = hasNotificationPermission()

            val totals = withContext(Dispatchers.IO) {
                countTotalsForSources(sources)
            }

            showTransferProgress(
                copiedFiles = 0,
                totalFiles = totals.totalFiles,
                copiedBytes = 0,
                totalBytes = totals.totalBytes,
                currentName = "",
                operationLabel = operationLabel,
                indeterminate = totals.totalFiles <= 0
            )
            updateTransferNotification(0, totals.totalFiles, 0, totals.totalBytes, "", operationLabel)

            val progress = TransferProgress(
                totalFiles = totals.totalFiles,
                totalBytes = totals.totalBytes
            )

            var lastProgressUpdateMs = 0L
            val result = withContext(Dispatchers.IO) {
                performTransfer(sources, targetDir, isCut, progress) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS || progress.copiedFiles == progress.totalFiles) {
                        lastProgressUpdateMs = now
                        runOnUiThread {
                            showTransferProgress(
                                copiedFiles = progress.copiedFiles,
                                totalFiles = progress.totalFiles,
                                copiedBytes = progress.copiedBytes,
                                totalBytes = progress.totalBytes,
                                currentName = progress.currentName,
                                operationLabel = operationLabel,
                                indeterminate = progress.totalFiles <= 0
                            )
                            updateTransferNotification(
                                copiedFiles = progress.copiedFiles,
                                totalFiles = progress.totalFiles,
                                copiedBytes = progress.copiedBytes,
                                totalBytes = progress.totalBytes,
                                currentName = progress.currentName,
                                operationLabel = operationLabel
                            )
                        }
                    }
                }
            }

            hideTransferProgress()
            completeTransferNotification(result.failedFiles == 0)

            transferInProgress = false
            invalidateOptionsMenu()

            if (result.failedFiles == 0) {
                if (isCut) {
                    clipboard.clear()
                    clipboardCut = false
                }
                Toast.makeText(
                    this@MainActivity,
                    if (isCut) R.string.transfer_move_complete else R.string.transfer_copy_complete,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.transfer_failed_with_count, result.failedFiles),
                    Toast.LENGTH_LONG
                ).show()
            }

            if (result.deletedAfterCutFailures > 0) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.transfer_cut_delete_failed_with_count, result.deletedAfterCutFailures),
                    Toast.LENGTH_LONG
                ).show()
            }

            loadCurrent()
        }
    }

    private fun countTotalsForSources(sources: List<DocumentFile>): TransferTotals {
        var files = 0
        var bytes = 0L
        sources.forEach { source ->
            val totals = countTotals(source)
            files += totals.totalFiles
            bytes += totals.totalBytes
        }
        return TransferTotals(files, bytes)
    }

    private fun countTotals(file: DocumentFile): TransferTotals {
        if (!file.exists()) {
            return TransferTotals(0, 0)
        }

        if (file.isDirectory) {
            var files = 0
            var bytes = 0L
            file.listFiles().forEach { child ->
                val childTotals = countTotals(child)
                files += childTotals.totalFiles
                bytes += childTotals.totalBytes
            }
            return TransferTotals(files, bytes)
        }

        return TransferTotals(
            totalFiles = 1,
            totalBytes = file.length().coerceAtLeast(0L)
        )
    }

    private fun countDeleteTotalsForTargets(targets: List<DocumentFile>): DeleteTotals {
        var items = 0
        var bytes = 0L
        targets.forEach { file ->
            val totals = countDeleteTotals(file)
            items += totals.totalItems
            bytes += totals.totalBytes
        }
        return DeleteTotals(items, bytes)
    }

    private fun countDeleteTotals(file: DocumentFile): DeleteTotals {
        if (!file.exists()) {
            return DeleteTotals(0, 0)
        }

        if (file.isDirectory) {
            var items = 1
            var bytes = 0L
            file.listFiles().forEach { child ->
                val childTotals = countDeleteTotals(child)
                items += childTotals.totalItems
                bytes += childTotals.totalBytes
            }
            return DeleteTotals(items, bytes)
        }

        return DeleteTotals(
            totalItems = 1,
            totalBytes = file.length().coerceAtLeast(0L)
        )
    }

    private fun deleteWithProgress(targets: List<DocumentFile>) {
        if (transferInProgress) {
            Toast.makeText(this, R.string.transfer_already_running, Toast.LENGTH_SHORT).show()
            return
        }

        if (targets.isEmpty()) return

        val operationLabel = getString(R.string.transfer_deleting)
        transferInProgress = true
        invalidateOptionsMenu()

        showTransferProgress(
            copiedFiles = 0,
            totalFiles = 0,
            copiedBytes = 0,
            totalBytes = 0,
            currentName = "",
            operationLabel = operationLabel,
            indeterminate = true
        )

        lifecycleScope.launch {
            canShowNotifications = hasNotificationPermission()

            val totals = withContext(Dispatchers.IO) {
                countDeleteTotalsForTargets(targets)
            }

            showTransferProgress(
                copiedFiles = 0,
                totalFiles = totals.totalItems,
                copiedBytes = 0,
                totalBytes = totals.totalBytes,
                currentName = "",
                operationLabel = operationLabel,
                indeterminate = totals.totalItems <= 0
            )
            updateTransferNotification(0, totals.totalItems, 0, totals.totalBytes, "", operationLabel)

            val progress = TransferProgress(
                totalFiles = totals.totalItems,
                totalBytes = totals.totalBytes
            )

            var lastProgressUpdateMs = 0L
            val failures = withContext(Dispatchers.IO) {
                performDelete(targets, progress) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS || progress.copiedFiles == progress.totalFiles) {
                        lastProgressUpdateMs = now
                        runOnUiThread {
                            showTransferProgress(
                                copiedFiles = progress.copiedFiles,
                                totalFiles = progress.totalFiles,
                                copiedBytes = progress.copiedBytes,
                                totalBytes = progress.totalBytes,
                                currentName = progress.currentName,
                                operationLabel = operationLabel,
                                indeterminate = progress.totalFiles <= 0
                            )
                            updateTransferNotification(
                                copiedFiles = progress.copiedFiles,
                                totalFiles = progress.totalFiles,
                                copiedBytes = progress.copiedBytes,
                                totalBytes = progress.totalBytes,
                                currentName = progress.currentName,
                                operationLabel = operationLabel
                            )
                        }
                    }
                }
            }

            hideTransferProgress()
            completeTransferNotification(failures == 0)

            transferInProgress = false
            invalidateOptionsMenu()

            if (failures == 0) {
                Toast.makeText(this@MainActivity, R.string.transfer_delete_complete, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.transfer_delete_failed_with_count, failures),
                    Toast.LENGTH_LONG
                ).show()
            }

            loadCurrent()
        }
    }

    private fun performDelete(
        targets: List<DocumentFile>,
        progress: TransferProgress,
        onProgressUpdate: () -> Unit
    ): Int {
        var failures = 0
        targets.forEach { target ->
            failures += deleteRecursively(target, progress, onProgressUpdate)
        }
        return failures
    }

    private fun deleteRecursively(
        file: DocumentFile,
        progress: TransferProgress,
        onProgressUpdate: () -> Unit
    ): Int {
        var failures = 0

        if (!file.exists()) {
            return 0
        }

        if (file.isDirectory) {
            file.listFiles().forEach { child ->
                failures += deleteRecursively(child, progress, onProgressUpdate)
            }
        } else {
            progress.copiedBytes += file.length().coerceAtLeast(0L)
        }

        progress.currentName = file.name ?: ""
        val deleted = try {
            file.delete()
        } catch (_: Exception) {
            false
        }
        progress.copiedFiles += 1
        onProgressUpdate()
        if (!deleted) {
            failures += 1
        }

        return failures
    }

    private fun performTransfer(
        sources: List<DocumentFile>,
        targetDir: DocumentFile,
        isCut: Boolean,
        progress: TransferProgress,
        onProgressUpdate: () -> Unit
    ): TransferResult {
        var failures = 0

        sources.forEach { source ->
            failures += if (source.isDirectory) {
                copyDirectory(source, targetDir, progress, onProgressUpdate)
            } else {
                copyFile(source, targetDir, progress, onProgressUpdate)
            }
        }

        var deleteFailures = 0
        if (isCut && failures == 0) {
            sources.forEach { source ->
                if (!source.delete()) {
                    deleteFailures += 1
                }
            }
        }

        return TransferResult(failures, deleteFailures)
    }

    private fun copyDirectory(
        source: DocumentFile,
        target: DocumentFile,
        progress: TransferProgress,
        onProgressUpdate: () -> Unit
    ): Int {
        val newDir = target.createDirectory(source.name ?: "Folder") ?: return countTotals(source).totalFiles

        var failures = 0
        source.listFiles().forEach { child ->
            failures += if (child.isDirectory) {
                copyDirectory(child, newDir, progress, onProgressUpdate)
            } else {
                copyFile(child, newDir, progress, onProgressUpdate)
            }
        }
        return failures
    }

    private fun copyFile(
        source: DocumentFile,
        targetDir: DocumentFile,
        progress: TransferProgress,
        onProgressUpdate: () -> Unit
    ): Int {
        return try {
            val name = source.name ?: return 1
            val type = source.type ?: "application/octet-stream"
            val dest = targetDir.createFile(type, name) ?: return 1

            contentResolver.openInputStream(source.uri)?.use { inp ->
                contentResolver.openOutputStream(dest.uri)?.use { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = inp.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        progress.copiedBytes += read
                        progress.currentName = name
                        onProgressUpdate()
                    }
                    out.flush()
                } ?: return 1
            } ?: return 1

            progress.copiedFiles += 1
            progress.currentName = name
            onProgressUpdate()
            0
        } catch (_: Exception) {
            1
        }
    }

    private fun showTransferProgress(
        copiedFiles: Int,
        totalFiles: Int,
        copiedBytes: Long,
        totalBytes: Long,
        currentName: String,
        operationLabel: String,
        indeterminate: Boolean
    ) {
        binding.transferContainer.visibility = View.VISIBLE

        val fileProgressText = if (totalFiles > 0) {
            getString(R.string.transfer_file_progress, copiedFiles, totalFiles)
        } else {
            getString(R.string.transfer_calculating)
        }

        val bytesProgressText = if (totalBytes > 0) {
            getString(
                R.string.transfer_bytes_progress,
                Formatter.formatShortFileSize(this, copiedBytes),
                Formatter.formatShortFileSize(this, totalBytes)
            )
        } else {
            getString(R.string.transfer_scanning)
        }

        val currentFileText = if (currentName.isNotBlank()) {
            getString(R.string.transfer_current_file, currentName)
        } else {
            ""
        }

        binding.transferStatus.text = listOf(operationLabel, fileProgressText, bytesProgressText, currentFileText)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        binding.transferProgress.isIndeterminate = indeterminate
        if (!indeterminate && totalBytes > 0) {
            val percent = ((copiedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            binding.transferProgress.progress = percent
        } else if (!indeterminate && totalFiles > 0) {
            val percent = ((copiedFiles * 100) / totalFiles).coerceIn(0, 100)
            binding.transferProgress.progress = percent
        }
    }

    private fun hideTransferProgress() {
        binding.transferContainer.visibility = View.GONE
    }

    private fun createTransferNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            TRANSFER_NOTIFICATION_CHANNEL_ID,
            getString(R.string.transfer_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.transfer_notification_channel_description)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun updateTransferNotification(
        copiedFiles: Int,
        totalFiles: Int,
        copiedBytes: Long,
        totalBytes: Long,
        currentName: String,
        operationLabel: String
    ) {
        if (!canShowNotifications) return

        val builder = NotificationCompat.Builder(this, TRANSFER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file)
            .setContentTitle(operationLabel)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (totalBytes > 0) {
            val percent = ((copiedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            builder
                .setContentText(
                    getString(
                        R.string.transfer_notification_progress,
                        copiedFiles,
                        totalFiles,
                        Formatter.formatShortFileSize(this, copiedBytes),
                        Formatter.formatShortFileSize(this, totalBytes)
                    )
                )
                .setProgress(100, percent, false)
        } else if (totalFiles > 0) {
            val percent = ((copiedFiles * 100) / totalFiles).coerceIn(0, 100)
            builder
                .setContentText(getString(R.string.transfer_notification_progress_files_only, copiedFiles, totalFiles))
                .setProgress(100, percent, false)
        } else {
            builder
                .setContentText(getString(R.string.transfer_notification_progress_files_only, copiedFiles, totalFiles))
                .setProgress(0, 0, true)
        }

        if (currentName.isNotBlank()) {
            builder.setSubText(currentName)
        }

        NotificationManagerCompat.from(this).notify(TRANSFER_NOTIFICATION_ID, builder.build())
    }

    private fun completeTransferNotification(success: Boolean) {
        if (!canShowNotifications) return

        val builder = NotificationCompat.Builder(this, TRANSFER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file)
            .setContentTitle(
                if (success) {
                    getString(R.string.transfer_notification_complete)
                } else {
                    getString(R.string.transfer_notification_failed)
                }
            )
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(this).notify(TRANSFER_NOTIFICATION_ID, builder.build())
    }
}
