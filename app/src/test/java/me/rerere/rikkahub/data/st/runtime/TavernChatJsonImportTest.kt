package me.rerere.rikkahub.data.st.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * SillyTavern `.jsonl` 聊天记录导入的行为钉子。
 *
 * 覆盖：header 识别与标题回退链、多格式 send_date 解析与单调化、
 * is_system / narrator 的隐藏保留语义、图片只计数不附件、重复导入为追加而非覆盖。
 */
class TavernChatJsonImportTest {

    @Test
    fun `imports header roles swipes and title fallback`() {
        val jsonl = """
            {"user_name":"User","character_name":"Alice","create_date":"2024-1-5 @10h 0m 0s 0ms"}
            {"name":"User","is_user":true,"mes":"hi","send_date":1700000000}
            {"name":"Alice","mes":"hello","swipes":["hello","hey"],"swipe_id":1,"send_date":1700000001000}
        """.trimIndent()
        val result = importConversationFromStJsonl(jsonl)
        val conversation = result.conversation

        assertEquals("Alice", conversation.title)
        assertEquals(2, result.messageCount)
        assertEquals(0, result.skippedImages)
        assertEquals(2, conversation.messageNodes.size)

        val first = conversation.messageNodes[0].messages.single()
        assertEquals(MessageRole.USER, first.role)
        assertEquals("hi", first.toText())

        val secondNode = conversation.messageNodes[1]
        assertEquals(2, secondNode.messages.size)
        assertEquals(1, secondNode.selectIndex)
        assertEquals("hey", secondNode.messages[secondNode.selectIndex].toText())

        // create_date 走 ST 遗留 humanized 格式解析
        val expectedCreate = LocalDateTime.of(2024, 1, 5, 10, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
        assertEquals(expectedCreate, conversation.createAt)
    }

    @Test
    fun `send_date formats are parsed and ordering normalized`() {
        val jsonl = """
            {"chat_metadata":{}}
            {"mes":"a","is_user":true,"send_date":1700000001000}
            {"mes":"b","send_date":1700000000}
            {"mes":"c"}
        """.trimIndent()
        val result = importConversationFromStJsonl(jsonl)
        val millis = result.conversation.messageNodes.map { node ->
            node.messages.single()
                .createdAt
                .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }

        // a 的毫秒时间戳原样保留；b 的秒级时间戳归一后早于 a，被强制排到 a 之后 +1ms；
        // c 缺失时间接着 +1ms，楼层顺序保持稳定。
        assertEquals(1700000001000L, millis[0])
        assertEquals(1700000001001L, millis[1])
        assertEquals(1700000001002L, millis[2])
    }

    @Test
    fun `is_system and narrator messages are kept as hidden assistant messages`() {
        val jsonl = """
            {"chat_metadata":{}}
            {"mes":"secret note","is_system":true}
            {"mes":"*夜色渐深*","extra":{"type":"Narrator"}}
            {"mes":"normal","is_user":true}
        """.trimIndent()
        val result = importConversationFromStJsonl(jsonl)
        val nodes = result.conversation.messageNodes

        assertEquals(3, nodes.size)
        val hidden = nodes[0].messages.single()
        assertTrue(hidden.isHidden)
        assertEquals(MessageRole.ASSISTANT, hidden.role)
        val narrator = nodes[1].messages.single()
        assertTrue(narrator.isHidden)
        assertEquals(MessageRole.ASSISTANT, narrator.role)
        assertFalse(nodes[2].messages.single().isHidden)
    }

    @Test
    fun `images are counted as skipped and never attached`() {
        val jsonl = """
            {"chat_metadata":{}}
            {"mes":"old","extra":{"image":"a.png","image_swipes":["a.png","b.png"]}}
            {"mes":"new","extra":{"media":[{"type":"image","url":"x.png"},{"type":"audio","url":"s.wav"}]}}
            {"mes":"rp","extra":{"rpclient":{"schema_version":1,"images":[{"mime_type":"image/png","data":"AA=="},{"mime_type":"image/png","data":"BB=="}]}}}
            {"mes":"plain"}
        """.trimIndent()
        val result = importConversationFromStJsonl(jsonl)

        // 旧版去重后 2 张 + 新版 1 张图片 + RPClient 私有 2 张
        assertEquals(5, result.skippedImages)
        result.conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                assertTrue(message.parts.none { it is UIMessagePart.Image })
            }
        }
    }

    @Test
    fun `rpclient header metadata wins and repeated imports add distinct conversations`() {
        val jsonl = """
            {"user_name":"unused","character_name":"unused","chat_metadata":{"rpclient":{"schema_version":1,"title":"我的存档","create_time":1234567890123,"latest_time":1234567899999}}}
            {"mes":"hello","is_user":true,"send_date":1234567890123}
        """.trimIndent()
        val first = importConversationFromStJsonl(jsonl, title = "file-name")
        val second = importConversationFromStJsonl(jsonl, title = "file-name")

        // RPClient 私有命名空间的标题与时间优先；unused 角色名不参与回退
        assertEquals("我的存档", first.conversation.title)
        assertEquals(1234567890123L, first.conversation.createAt.toEpochMilli())
        assertEquals(1234567899999L, first.conversation.updateAt.toEpochMilli())

        // 重复导入是加法：新会话 ID，互不覆盖
        assertTrue(first.conversation.id != second.conversation.id)
        assertEquals(first.conversation.title, second.conversation.title)
        assertEquals(first.messageCount, second.messageCount)
    }

    @Test
    fun `non message lines are ignored and default title applied`() {
        val jsonl = """
            {"chat_metadata":{}}
            {"foo":"bar"}
            {"mes":"ok","is_user":true}
            not-json-at-all
        """.trimIndent()
        val result = importConversationFromStJsonl(jsonl)

        assertEquals(1, result.messageCount)
        assertEquals("ok", result.conversation.messageNodes.single().messages.single().toText())
        assertEquals("Imported ST Chat", result.conversation.title)
    }
}
