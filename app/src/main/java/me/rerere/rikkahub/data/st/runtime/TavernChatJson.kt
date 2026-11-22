package me.rerere.rikkahub.data.st.runtime

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode

private val tavernChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 将当前 [Conversation] 序列化为 SillyTavern `getContext().chat` 标准数组格式（`ChatMessage[]`）。
 *
 * 每项结构（对齐 SillyTavern `script.js` 与 `JS-Slash-Runner` / `MC-记忆表格` 契约）：
 * - `mes`: 当前选中 swipe 的纯文本
 * - `name`: 发言者名称（用户昵称或助手名称）
 * - `is_user`: 是否为用户消息
 * - `is_system`: 是否为隐藏消息（对齐 ST `/hide` 的 `is_system` 字段）
 * - `swipe_id`: 当前选中分支下标（`node.selectIndex`）
 * - `swipes`: 全部分支文本列表（`node.messages.map { it.toText() }`）
 * - `extra`: `{}`（供扩展挂载附加元数据）
 */
/**
 * 楼层 JSON 节点级 LRU 缓存（Task F & F-04 修复）：
 * - 在 200~500 楼长对话中，每次发送消息、`fireEvent` 或流式输出 token 时，`RikkaBridge.getChatJson()`
 *   都会被调用；但绝大部分历史楼层的 `MessageNode` 引用与内容完全未变。
 * - `NodeCacheKey` 完整覆盖被序列化进单楼层 JSON 的全部字段：
 *   `(nodeId, index, selectIndex, messagesSize, currentMsgId, currentText, swipes, sendDate, isUser, isHidden, speakerName, altGreetingCount)`。
 * - 这样无论是修改未选中的后台分支（non-selected swipe）、修改角色卡 `alternateGreetings` 文本、修改发送时间或发言者角色，
 *   还是遇到相同 32-bit `hashCode()` 的不同字符串（如 `"Aa"` 与 `"BB"`），缓存键均能通过精确相等性比较立即识别并重建，绝不返回陈旧 JSON！
 */
internal object TavernChatJsonCache {
    private const val MAX_NODE_ENTRIES = 1024

    data class NodeCacheKey(
        val nodeId: String,
        val index: Int,
        val selectIndex: Int,
        val messagesSize: Int,
        val currentMsgId: String,
        val currentText: String,
        val swipes: List<String>,
        val sendDate: String,
        val isUser: Boolean,
        val isHidden: Boolean,
        val speakerName: String,
        val altGreetingCount: Int,
    )

    private val lock = Any()
    private val nodeMap = object : java.util.LinkedHashMap<NodeCacheKey, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeCacheKey, String>?): Boolean =
            size > MAX_NODE_ENTRIES
    }

    @Volatile
    var lastCacheHits: Int = 0
        internal set

    fun getOrBuildNodeJson(
        index: Int,
        node: MessageNode,
        current: UIMessage,
        speakerName: String,
        alternateGreetings: List<String>,
    ): Pair<String, Boolean> {
        val currentText = current.toText()
        val isUser = current.role == MessageRole.USER
        val effectiveAltCount = if (index == 0 && !isUser && node.messages.size == 1) alternateGreetings.size else 0
        val swipesList = if (effectiveAltCount > 0) {
            listOf(currentText) + alternateGreetings
        } else if (node.messages.size == 1) {
            listOf(currentText)
        } else {
            node.messages.map { it.toText() }
        }
        val sendDate = current.createdAt.toString()
        val key = NodeCacheKey(
            nodeId = node.id.toString(),
            index = index,
            selectIndex = node.selectIndex,
            messagesSize = node.messages.size,
            currentMsgId = current.id.toString(),
            currentText = currentText,
            swipes = swipesList,
            sendDate = sendDate,
            isUser = isUser,
            isHidden = current.isHidden,
            speakerName = speakerName,
            altGreetingCount = effectiveAltCount,
        )
        synchronized(lock) {
            nodeMap[key]?.let { return it to true }
        }
        val built = buildJsonObject {
            put("message_id", index)
            put("name", speakerName)
            put("is_user", isUser)
            put("is_system", current.isHidden)
            put("send_date", sendDate)
            put("mes", currentText)
            put("swipe_id", node.selectIndex)
            put(
                "swipes",
                buildJsonArray {
                    swipesList.forEach { s -> add(JsonPrimitive(s)) }
                },
            )
            put("extra", buildJsonObject {})
        }.toString()
        synchronized(lock) {
            nodeMap[key] = built
        }
        return built to false
    }

    fun clear() {
        synchronized(lock) {
            nodeMap.clear()
            lastCacheHits = 0
        }
    }
}

fun buildTavernChatJson(
    conversation: Conversation?,
    settings: Settings?,
): String {
    if (conversation == null || conversation.messageNodes.isEmpty()) return "[]"
    val assistant = settings?.getAssistantById(conversation.assistantId)
        ?: settings?.getCurrentAssistant()
    val charName = assistant?.tavernData?.name?.ifBlank { assistant.name }
        ?: assistant?.name?.ifBlank { "Assistant" }
        ?: "Assistant"
    val activePersona = settings?.personas?.firstOrNull { it.id == settings.activePersonaId }
    val userName = activePersona?.name?.ifBlank { settings.displaySetting.userNickname }
        ?: settings?.displaySetting?.userNickname?.ifBlank { "User" }
        ?: "User"

    val alternateGreetings = assistant?.tavernData?.alternateGreetings
        ?.filter { it.isNotBlank() }
        .orEmpty()

    val sb = StringBuilder(conversation.messageNodes.size * 256)
    sb.append('[')
    var first = true
    var hits = 0
    conversation.messageNodes.forEachIndexed { index, node ->
        val current = node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull()
        if (current != null) {
            val isUser = current.role == MessageRole.USER
            val speakerName = current.name?.takeIf { it.isNotBlank() }
                ?: if (isUser) userName else charName
            val (nodeJson, hit) = TavernChatJsonCache.getOrBuildNodeJson(
                index = index,
                node = node,
                current = current,
                speakerName = speakerName,
                alternateGreetings = alternateGreetings,
            )
            if (hit) hits++
            if (!first) sb.append(',')
            first = false
            sb.append(nodeJson)
        }
    }
    sb.append(']')
    TavernChatJsonCache.lastCacheHits = hits
    return sb.toString()
}

/**
 * 将 JS 侧 `setChatMessages` / `setChatMessage` / `saveChat` 回传的增量更新数组应用到 [Conversation]。
 *
 * 支持：
 * 1. 仅传 `swipe_id`（如角色卡开场白按钮点击切换内置预设开场白 `setChatMessages([{ message_id: 0, swipe_id: 2 }])`）：
 *    若第 0 楼当前仅有 1 条消息但角色卡包含 `alternateGreetings`，自动将 `alternateGreetings` 展开为该楼层的 `messages` 分支并切换到目标 `swipe_id`；
 * 2. 传 `swipes` 数组和/或 `mes`/`message` 文本：同步更新分支文本与选中下标；
 * 3. 传 `is_system` / `name`：同步更新隐藏状态与发言者名称。
 */
fun applyTavernChatUpdates(
    conversation: Conversation,
    updatesJson: String,
    alternateGreetings: List<String> = emptyList(),
): Conversation? {
    if (updatesJson.isBlank()) return null
    val arr = runCatching { tavernChatJson.parseToJsonElement(updatesJson) as? JsonArray }.getOrNull()
        ?: return null
    if (arr.isEmpty()) return null

    val nodes = conversation.messageNodes.toMutableList()
    var mutated = false

    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        val messageId = obj["message_id"]?.jsonPrimitiveOrNull?.intOrNull
            ?: obj["id"]?.jsonPrimitiveOrNull?.intOrNull
            ?: continue
        if (messageId !in nodes.indices) continue

        val node = nodes[messageId]
        val currentMsg = node.messages.getOrNull(node.selectIndex) ?: node.messages.firstOrNull() ?: continue

        val swipesArr = obj["swipes"] as? JsonArray
        val mesText = obj["mes"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: obj["message"]?.jsonPrimitiveOrNull?.contentOrNull
        val swipeId = obj["swipe_id"]?.jsonPrimitiveOrNull?.intOrNull
        val isSystem = obj["is_system"]?.jsonPrimitiveOrNull?.booleanOrNull
        val newName = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull

        if (swipesArr == null && mesText == null && swipeId == null && isSystem == null && newName == null) {
            continue
        }

        val baseMessages: List<UIMessage> = if (
            swipesArr == null &&
            mesText == null &&
            swipeId != null &&
            swipeId >= node.messages.size &&
            messageId == 0 &&
            currentMsg.role == MessageRole.ASSISTANT &&
            alternateGreetings.isNotEmpty()
        ) {
            val firstText = node.messages.firstOrNull()?.toText().orEmpty()
            val allGreetingTexts = listOf(firstText) + alternateGreetings.filter { it.isNotBlank() }
            allGreetingTexts.mapIndexed { idx, gText ->
                val existing = node.messages.getOrNull(idx)
                existing?.copy(parts = replaceTextParts(existing.parts, gText))
                    ?: currentMsg.copy(
                        id = kotlin.uuid.Uuid.random(),
                        parts = listOf(UIMessagePart.Text(gText)),
                    )
            }
        } else {
            node.messages
        }

        val updatedMessages: List<UIMessage> = when {
            swipesArr != null && swipesArr.isNotEmpty() -> {
                val swipeTexts = swipesArr.map { it.jsonPrimitiveOrNull?.contentOrNull ?: "" }
                val targetSwipeIdx = (swipeId ?: node.selectIndex).coerceIn(0, swipeTexts.lastIndex)
                swipeTexts.mapIndexed { idx, sText ->
                    val effectiveText = if (idx == targetSwipeIdx && mesText != null) mesText else sText
                    val baseMsg = baseMessages.getOrNull(idx) ?: currentMsg.copy(id = kotlin.uuid.Uuid.random())
                    baseMsg.withUpdatedFields(
                        newText = effectiveText,
                        isSystem = if (idx == targetSwipeIdx) isSystem else null,
                        newName = if (idx == targetSwipeIdx) newName else null,
                    )
                }
            }
            mesText != null -> {
                val targetIdx = (swipeId ?: node.selectIndex).coerceIn(0, (baseMessages.size - 1).coerceAtLeast(0))
                if (baseMessages.isEmpty()) {
                    listOf(
                        currentMsg.withUpdatedFields(
                            newText = mesText,
                            isSystem = isSystem,
                            newName = newName,
                        )
                    )
                } else {
                    baseMessages.mapIndexed { idx, msg ->
                        if (idx == targetIdx) {
                            msg.withUpdatedFields(
                                newText = mesText,
                                isSystem = isSystem,
                                newName = newName,
                            )
                        } else {
                            msg
                        }
                    }
                }
            }
            else -> {
                val targetIdx = (swipeId ?: node.selectIndex).coerceIn(0, (baseMessages.size - 1).coerceAtLeast(0))
                baseMessages.mapIndexed { idx, msg ->
                    if (idx == targetIdx && (isSystem != null || newName != null)) {
                        msg.withUpdatedFields(
                            newText = null,
                            isSystem = isSystem,
                            newName = newName,
                        )
                    } else {
                        msg
                    }
                }
            }
        }

        val newSelectIndex = (swipeId ?: node.selectIndex).coerceIn(0, (updatedMessages.size - 1).coerceAtLeast(0))
        val newNode = node.copy(
            messages = updatedMessages,
            selectIndex = newSelectIndex,
        )
        if (newNode != node) {
            nodes[messageId] = newNode
            mutated = true
        }
    }

    return if (mutated) conversation.copy(messageNodes = nodes) else null
}

/**
 * 解析 JSR `createChatMessages` 传入的待插入楼层数组。
 */
data class RuntimeChatInsertItem(
    val role: MessageRole,
    val message: String,
    val name: String? = null,
    val data: JsonObject? = null,
)

fun parseRuntimeChatInserts(jsonText: String): List<RuntimeChatInsertItem> {
    if (jsonText.isBlank()) return emptyList()
    val arr = runCatching { tavernChatJson.parseToJsonElement(jsonText) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val roleStr = obj["role"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()?.trim() ?: "assistant"
        val role = when (roleStr) {
            "user", "human" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        }
        val msg = obj["message"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: obj["mes"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: ""
        val name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        val dataObj = obj["data"] as? JsonObject
        RuntimeChatInsertItem(
            role = role,
            message = msg,
            name = name,
            data = dataObj,
        )
    }
}

private fun UIMessage.withUpdatedFields(
    newText: String?,
    isSystem: Boolean?,
    newName: String?,
): UIMessage {
    var updated = if (newText != null) {
        copy(parts = replaceTextParts(parts, newText))
    } else {
        this
    }
    if (isSystem != null) {
        updated = if (isSystem) updated.withHidden() else updated.withoutHidden()
    }
    if (!newName.isNullOrBlank()) {
        updated = updated.copy(name = newName)
    }
    return updated
}

private fun replaceTextParts(parts: List<UIMessagePart>, newText: String): List<UIMessagePart> {
    val nonText = parts.filterNot { it is UIMessagePart.Text }
    return listOf(UIMessagePart.Text(newText)) + nonText
}

private val kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/**
 * 将 Conversation 导出为官方 SillyTavern `.jsonl` 聊天记录格式（首行为对话元数据头，后续每行为一条消息 JSON）。
 */
fun exportConversationToStJsonl(
    conversation: Conversation,
    userName: String = "User",
    characterName: String = "Assistant",
    chatMetadata: JsonObject = buildJsonObject {},
): String {
    val lines = mutableListOf<String>()
    val header = buildJsonObject {
        put("user_name", JsonPrimitive(userName))
        put("character_name", JsonPrimitive(characterName))
        put("create_date", JsonPrimitive(conversation.createAt.toString()))
        put("chat_metadata", chatMetadata)
    }
    lines += header.toString()
    val chatArrayJson = buildTavernChatJson(conversation, null)
    val arr = runCatching { Json.parseToJsonElement(chatArrayJson) as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
    for (el in arr) {
        val obj = el as? JsonObject ?: continue
        lines += obj.toString()
    }
    return lines.joinToString("\n")
}

/**
 * ST `.jsonl` 聊天记录导入结果。
 *
 * @param conversation 解析出的会话；每次调用都生成全新 ID，重复导入即追加新会话、不覆盖旧会话。
 * @param messageCount 导入的消息楼层数。
 * @param skippedImages 文件里出现但本次导入未恢复的图片数量（内嵌与路径引用均计入）。
 */
data class StJsonlImportResult(
    val conversation: Conversation,
    val messageCount: Int,
    val skippedImages: Int,
)

/** epoch 秒/毫秒归一阈值：绝对值小于该值按秒处理，与 RPClient 归档规则一致。 */
private const val ST_JSONL_SECONDS_EPOCH_THRESHOLD = 100_000_000_000L

/** 单份聊天存档允许的最大消息楼层数，超过直接判定为非法文件。 */
private const val ST_JSONL_MAX_MESSAGES = 100_000

/** SillyTavern humanizedDateTime() 的遗留格式，如 `2024-1-5 @14h 30m 25s 500ms`。 */
private val stJsonlLegacySendDateRegex = Regex(
    pattern = """^(\d{4})-(\d{1,2})-(\d{1,2})\s*@?(\d{1,2})h\s+(\d{1,2})m\s+(\d{1,2})s\s+(\d{1,3})ms$""",
)

/**
 * 从官方 SillyTavern `.jsonl` 聊天记录文本无损导入为 Conversation。
 *
 * - 首行 header（user_name / character_name / chat_metadata）提供标题与创建时间；
 *   RPClient 私有命名空间 `chat_metadata.rpclient` 的 title / create_time / latest_time 优先。
 * - 每行消息支持多 swipes（映射为 MessageNode 分支）、is_user、is_system（保留为隐藏消息）、
 *   `extra.type = narrator` 旁白（保留为隐藏的 assistant 消息）与多格式 send_date 时间戳。
 * - 时间戳按 RPClient 规则单调化：乱序或缺失时取前一条 +1ms，保证导入后楼层顺序稳定。
 * - 图片字段只统计数量（[StJsonlImportResult.skippedImages]），不恢复图片字节。
 */
fun importConversationFromStJsonl(
    jsonl: String,
    assistantId: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
    title: String = "",
): StJsonlImportResult {
    val rawLines = jsonl.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val nodes = mutableListOf<me.rerere.rikkahub.data.model.MessageNode>()
    var skippedImages = 0
    var headerCharacterName: String? = null
    var headerCreateDate: JsonElement? = null
    var rpClientMeta: JsonObject? = null
    var previousMillis = Long.MIN_VALUE
    var firstMillis: Long? = null
    var lastMillis: Long? = null

    for ((idx, line) in rawLines.withIndex()) {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
        // 首行若是 header（无 mes 且含 user_name / character_name / chat_metadata）则只记录元数据
        if (idx == 0 && !obj.containsKey("mes") &&
            (obj.containsKey("user_name") || obj.containsKey("character_name") || obj.containsKey("chat_metadata"))
        ) {
            headerCharacterName = (obj["character_name"] as? JsonPrimitive)?.contentOrNull
                ?.takeUnless { it.isBlank() || it.equals("unused", ignoreCase = true) }
            headerCreateDate = obj["create_date"]
            rpClientMeta = (obj["chat_metadata"] as? JsonObject)?.get("rpclient") as? JsonObject
            continue
        }
        val mes = (obj["mes"] as? JsonPrimitive)?.contentOrNull ?: continue
        require(nodes.size < ST_JSONL_MAX_MESSAGES) { "Chat archive has too many messages" }
        skippedImages += countStJsonlImages(obj)

        val isUser = (obj["is_user"] as? JsonPrimitive)?.booleanOrNull == true
        val isSystemHidden = (obj["is_system"] as? JsonPrimitive)?.booleanOrNull == true
        // ST 的 is_system 表示 /hide 隐藏楼层；narrator 旁白同样按隐藏 assistant 消息保留，内容不丢。
        val isNarrator = (obj["extra"] as? JsonObject)?.get("type")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.equals("narrator", ignoreCase = true) == true
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
        val swipesArr = (obj["swipes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val swipeTexts = if (!swipesArr.isNullOrEmpty()) swipesArr else listOf(mes)
        val swipeId = ((obj["swipe_id"] as? JsonPrimitive)?.intOrNull ?: 0)
            .coerceIn(0, (swipeTexts.size - 1).coerceAtLeast(0))

        // 时间归一化：可解析且晚于前一条才采用，否则沿用前一条 +1ms，保证楼层顺序稳定。
        val parsedMillis = parseStJsonlTimestamp(obj["send_date"])
        val effectiveMillis = when {
            parsedMillis != null && parsedMillis > previousMillis -> parsedMillis
            previousMillis == Long.MIN_VALUE -> System.currentTimeMillis()
            else -> previousMillis + 1L
        }
        previousMillis = effectiveMillis
        if (firstMillis == null) firstMillis = effectiveMillis
        lastMillis = effectiveMillis

        val role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT
        val createdAt = millisToStJsonlLocalDateTime(effectiveMillis)
        val messages = swipeTexts.map { text ->
            val base = UIMessage(
                role = role,
                parts = listOf(UIMessagePart.Text(text)),
                name = name,
                createdAt = createdAt,
            )
            if (isSystemHidden || isNarrator) base.withHidden() else base.withoutHidden()
        }
        nodes += me.rerere.rikkahub.data.model.MessageNode(
            id = kotlin.uuid.Uuid.random(),
            messages = messages,
            selectIndex = swipeId,
        )
    }

    val rpTitle = rpClientMeta?.stringField("title")
    val resolvedTitle = rpTitle
        ?: title.trim().takeIf { it.isNotEmpty() }
        ?: headerCharacterName
        ?: "Imported ST Chat"
    val createMillis = rpClientMeta?.longField("create_time")?.let(::normalizeStJsonlEpoch)
        ?: headerCreateDate?.let(::parseStJsonlTimestamp)
        ?: firstMillis
        ?: System.currentTimeMillis()
    val updateMillis = maxOf(
        rpClientMeta?.longField("latest_time")?.let(::normalizeStJsonlEpoch) ?: createMillis,
        lastMillis ?: createMillis,
    )

    return StJsonlImportResult(
        conversation = Conversation(
            id = kotlin.uuid.Uuid.random(),
            assistantId = assistantId,
            title = resolvedTitle,
            messageNodes = nodes,
            createAt = Instant.ofEpochMilli(createMillis),
            updateAt = Instant.ofEpochMilli(updateMillis),
        ),
        messageCount = nodes.size,
        skippedImages = skippedImages,
    )
}

private fun JsonObject.stringField(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.longField(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull

/** 解析 send_date / create_date 等时间字段为 epoch 毫秒；兼容数字、ISO 时间与 ST 遗留格式。 */
private fun parseStJsonlTimestamp(element: JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.longOrNull?.let(::normalizeStJsonlEpoch)
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return normalizeStJsonlEpoch(it) }
    runCatching { return Instant.parse(text).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching {
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    stJsonlLegacySendDateRegex.matchEntire(text)?.destructured
        ?.let { (year, month, day, hour, minute, second, millis) ->
            runCatching {
                return LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    second.toInt(),
                    millis.toInt() * 1_000_000,
                )
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
    return null
}

private fun normalizeStJsonlEpoch(value: Long): Long =
    if (value in -ST_JSONL_SECONDS_EPOCH_THRESHOLD..ST_JSONL_SECONDS_EPOCH_THRESHOLD) value * 1000L else value

private fun millisToStJsonlLocalDateTime(millis: Long): kotlinx.datetime.LocalDateTime =
    kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())

/**
 * 统计一行消息里的图片数量（仅用于导入提示，本导入器不恢复图片字节）。
 * RPClient 私有 images 数组存在时优先计数，避免与酒馆原生字段重复统计。
 */
private fun countStJsonlImages(obj: JsonObject): Int {
    val extra = obj["extra"] as? JsonObject ?: return 0
    val rpImages = (extra["rpclient"] as? JsonObject)?.get("images") as? JsonArray
    if (rpImages != null) return rpImages.size
    var count = 0
    (extra["media"] as? JsonArray)?.forEach { element ->
        val item = element as? JsonObject ?: return@forEach
        if (item.stringField("type")?.equals("image", ignoreCase = true) == true) count++
    }
    val urls = buildList {
        (extra["image_swipes"] as? JsonArray)?.forEach { element ->
            (element as? JsonPrimitive)?.contentOrNull?.let { add(it) }
        }
        extra.stringField("image")?.let { add(it) }
    }.filter { it.isNotBlank() }.distinct()
    count += urls.size
    return count
}
