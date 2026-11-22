package me.rerere.rikkahub.ui.pages.backup

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.st.runtime.importConversationFromStJsonl
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportResult
import me.rerere.rikkahub.data.sync.importer.DeepSeekImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.UiState
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

private const val TAG = "BackupVM"

/** 单份 `.jsonl` 聊天存档的大小上限，与 RPClient 的导入限制一致。 */
private const val MAX_ST_JSONL_BYTES = 512L * 1024 * 1024

class BackupVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val backupManager: BackupManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        recordBackupTime()
        return file
    }

    /** 导出上游 rikkahub 能直接导入的备份，本地独有数据会被丢弃。 */
    suspend fun exportUpstreamToFile(): File {
        val file = backupManager.createBackup(
            includeDatabase = WebDavConfig.BackupItem.DATABASE in localBackupItems.value,
            includeFiles = WebDavConfig.BackupItem.FILES in localBackupItems.value,
            upstreamCompatible = true,
        )
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    suspend fun restoreFromDeepSeek(file: File): DeepSeekImportResult = withContext(Dispatchers.IO) {
        val currentSettings = settingsStore.awaitLoaded()
        DeepSeekImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            shouldImportConversation = { !conversationRepository.existsConversationById(it) },
            formatMissingAttachment = { name ->
                context.getString(R.string.backup_page_import_deepseek_missing_attachment, name)
            },
            saveAttachment = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation.bindConfig(currentSettings))
            },
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings {
            it.copy(
                providers = importProviders + it.providers,
            )
        }
    }

    /**
     * 从 SAF Uri 读取 SillyTavern `.jsonl` 聊天记录，导入为当前助手下的**全新会话**。
     *
     * 每次调用都生成随机会话 ID 并纯插入：先导入 a.jsonl 再导入 b.jsonl 会得到两个会话，
     * 后者不会覆盖或修改前者。文件里出现的图片只计数不恢复（.jsonl 往往不含图片字节）。
     */
    suspend fun importStJsonlFromUri(uri: Uri): StJsonlChatImportResult = withContext(Dispatchers.IO) {
        val currentSettings = settingsStore.awaitLoaded()
        val displayName = queryStJsonlDisplayName(uri)
        val parsed = importConversationFromStJsonl(
            jsonl = readStJsonlText(uri),
            assistantId = currentSettings.assistantId,
            title = displayName
                ?.removeSuffix(".jsonl")
                ?.removeSuffix(".json")
                .orEmpty(),
        )
        conversationRepository.insertConversation(parsed.conversation.bindConfig(currentSettings))
        Log.i(
            TAG,
            "importStJsonlFromUri: import 1 conversation (${parsed.messageCount} messages), " +
                "skip ${parsed.skippedImages} images"
        )
        StJsonlChatImportResult(
            title = parsed.conversation.title,
            messageCount = parsed.messageCount,
            skippedImages = parsed.skippedImages,
        )
    }

    private fun queryStJsonlDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }.getOrNull()

    /** 先按文件声明大小快速拒绝，读取时再用限流流兜底，防止超大文件撑爆内存。 */
    private fun readStJsonlText(uri: Uri): String {
        val tooLargeMessage = context.getString(R.string.backup_page_import_st_jsonl_too_large)
        val declaredSize = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        require(declaredSize <= MAX_ST_JSONL_BYTES) { tooLargeMessage }
        context.contentResolver.openInputStream(uri)?.use { input ->
            val limited = StJsonlSizeLimitedInputStream(input, MAX_ST_JSONL_BYTES, tooLargeMessage)
            return limited.readBytes().toString(Charsets.UTF_8)
        }
        error("Unable to read the SillyTavern chat archive")
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)

/** SillyTavern `.jsonl` 单文件导入结果，用于导入完成提示。 */
data class StJsonlChatImportResult(
    val title: String,
    val messageCount: Int,
    val skippedImages: Int,
)

/** 读取时累计字节数的限流流：超过上限立即失败，避免超大文件撑爆内存。 */
private class StJsonlSizeLimitedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val tooLargeMessage: String,
) : FilterInputStream(input) {
    private var totalBytes = 0L

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) recordBytes(1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count ->
            if (count > 0) recordBytes(count)
        }

    private fun recordBytes(byteCount: Int) {
        totalBytes += byteCount
        require(totalBytes <= maxBytes) { tooLargeMessage }
    }
}
