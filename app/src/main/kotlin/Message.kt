package land.melty.matrixappserviceqq

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.request.get
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import java.sql.Connection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.folivo.trixnity.client.api.MatrixApiClient
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.Event
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent as RMEC
import net.mamoe.mirai.Bot
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.Face
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageContent
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.buildMessageSource
import net.mamoe.mirai.message.data.content
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner

suspend fun MessageContent.toMessageEventContent(
        matrixApiClient: MatrixApiClient,
        config: Config
): MessageEventContent =
        when (this) {
            is Image -> {
                val client = HttpClient(CIO) { install(UserAgent) { agent = "QQClient" } }
                val response: HttpResponse = client.get(this.queryUrl())
                val bytes: ByteReadChannel = response.receive()
                val url =
                        matrixApiClient
                                .media
                                .upload(bytes, response.contentLength()!!, response.contentType()!!)
                                .getOrThrow()
                                .contentUri
                if (this.isEmoji) StickerMessageEventContent(this.content, url = url)
                else RMEC.ImageMessageEventContent(this.content, url = url)
            }
            is At -> {
                val ghost = Ghost.get(this.target, matrixApiClient, config)
                if (ghost == null) RMEC.TextMessageEventContent(this.content)
                else {
                    val link = Element("a")
                    link.attr("href", "https://matrix.to/#/${ghost.userId}")
                    link.appendText(ghost.nick)
                    RMEC.TextMessageEventContent(
                            body = ghost.nick,
                            format = "org.matrix.custom.html",
                            formattedBody = link.toString()
                    )
                }
            }
            is Face -> RMEC.TextMessageEventContent(this.content)
            else -> RMEC.TextMessageEventContent(this.content)
        }

/** Should only be called in bot.eventChannel.subscribeAlways<MessageEvent> */
suspend fun MessageChain.toMessageEventContents(
        matrixApiClient: MatrixApiClient,
        messageSourceKind: MessageSourceKind,
        roomId: RoomId,
        config: Config
): List<MessageEventContent> {
    val contents = this.filterIsInstance<MessageContent>()
    val result = mutableListOf<MessageEventContent>()
    for (content in contents) {
        val previous = result.lastOrNull()
        val current = content.toMessageEventContent(matrixApiClient, config)
        if (result.size == 0) result.add(current)
        // Concat previous and current if both m.text
        else if (previous is RMEC.TextMessageEventContent && current is RMEC.TextMessageEventContent
        ) {
            // Null if both are plain text, concat if either has formatted_body
            val formattedBody =
                    if (previous.formattedBody == null && current.formattedBody == null) null
                    else
                            (previous.formattedBody
                                    ?: TextNode(previous.body).toString()) +
                                    (current.formattedBody ?: TextNode(current.body).toString())
            result[result.size - 1] =
                    RMEC.TextMessageEventContent(
                            body = previous.body + current.body,
                            format = previous.format ?: current.format,
                            formattedBody = formattedBody,
                            relatesTo = previous.relatesTo
                    )
        } else result.add(current)
    }
    // Add rich reply
    val quoteReply = this.filterIsInstance<QuoteReply>().firstOrNull()
    if (quoteReply != null) {
        val eventId = Messages.getEventId(quoteReply.source, messageSourceKind)
        if (eventId != null) {
            // Should be safe to cast because eventIds in db are from messages
            @Suppress("UNCHECKED_CAST")
            @OptIn(ExperimentalSerializationApi::class)
            val originalEvent =
                    matrixApiClient.rooms.getEvent(roomId, eventId).getOrThrow() as
                            Event.MessageEvent<MessageEventContent>
            result.map {
                if (it is RMEC.TextMessageEventContent) it.addReplyTo(originalEvent) else it
            }
        }
    }
    return result
}

/** @see https://spec.matrix.org/v1.2/client-server-api/#rich-replies */
fun RMEC.TextMessageEventContent.addReplyTo(
        originalEvent: Event.MessageEvent<MessageEventContent>
): RMEC.TextMessageEventContent {
    val content = originalEvent.content
    val fallback: String =
            when (content) {
                is RMEC.TextMessageEventContent ->
                        content.body
                                .split("\n")
                                .filter { !it.startsWith("> ") }
                                .joinToString("\n> ")
                is RMEC.ImageMessageEventContent -> content.body
                is StickerMessageEventContent -> content.body
                else -> return this
            }
    // Build the new formatted body
    val mxReply = Element("mx-reply")
    val blockquote = Element("blockquote")
    val eventLink = Element("a")
    val senderLink = Element("a")
    eventLink.attr("href", "https://matrix.to/#/${originalEvent.roomId}/${originalEvent.id.full}")
    eventLink.appendText("In reply to")
    blockquote.appendChild(eventLink)
    senderLink.attr("href", "https://matrix.to/#/${originalEvent.sender}")
    senderLink.appendText(originalEvent.sender.toString())
    blockquote.appendChild(eventLink)
    blockquote.appendChild(senderLink)
    blockquote.appendChild(Element("br"))
    // Clean the original formatted body
    when (content) {
        is RMEC.TextMessageEventContent ->
                if (content.formattedBody != null) {
                    val parsed = Jsoup.parseBodyFragment(content.formattedBody!!)
                    // Jsoup cleaner does not remove the inner HTML of mx-reply
                    // Manually remove here
                    parsed.select("mx-reply").remove()
                    val body = Cleaner(CustomSafelists.matrix()).clean(parsed).body()
                    blockquote.appendChildren(body.children())
                } else blockquote.appendChild(TextNode(content.body))
        is RMEC.ImageMessageEventContent -> blockquote.appendChild(TextNode(content.body))
        is StickerMessageEventContent -> blockquote.appendChild(TextNode(content.body))
        else -> return this
    }
    mxReply.appendChild(blockquote)
    return RMEC.TextMessageEventContent(
            body = "> <${originalEvent.sender}> ${fallback}\n\n" + this.body,
            format = "org.matrix.custom.html",
            formattedBody =
                    mxReply.toString() + (this.formattedBody ?: TextNode(this.body).toString()),
            relatesTo =
                    RelatesTo.Unknown(
                            raw =
                                    JsonObject(
                                            mapOf(
                                                    "m.in_reply_to" to
                                                            JsonObject(
                                                                    mapOf(
                                                                            "event_id" to
                                                                                    JsonPrimitive(
                                                                                            originalEvent
                                                                                                    .id
                                                                                                    .full
                                                                                    )
                                                                    )
                                                            )
                                            )
                                    )
                    )
    )
}

// suspend fun MessageEventContent.toMessageChain(): MessageChain {}

object Messages {
    var connection: Connection? = null
    fun dbInit(connection: Connection) {
        this.connection = connection
        val statement = connection.createStatement()
        statement.executeUpdate(
                """
                    CREATE TABLE IF NOT EXISTS messages (
                        kind TEXT NOT NULL,
                        sender INTEGER NOT NULL,
                        target INTEGER NOT NULL,
                        id INTEGER NOT NULL,
                        internal_id INTEGER NOT NULL,
                        time INTEGER NOT NULL,
                        event_id TEXT NOT NULL
                    )
                """
        )
    }

    fun getEventId(source: MessageSource, kind: MessageSourceKind): EventId? {
        val statement =
                connection!!.prepareStatement(
                        """
                            SELECT event_id FROM messages
                                WHERE kind = ?
                                AND sender = ?
                                AND target = ?
                                AND id = ?
                                AND internal_id = ?
                                AND time = ?
                        """
                )
        statement.setString(1, kind.toString())
        statement.setLong(2, source.fromId)
        statement.setLong(3, source.targetId)
        statement.setInt(4, source.ids[0])
        statement.setInt(5, source.internalIds[0])
        statement.setInt(6, source.time)
        val rs = statement.executeQuery()
        if (rs.next() == false) return null
        return EventId(rs.getString("event_id"))
    }

    fun getMessageSource(eventId: EventId, bot: Bot): MessageSource? {
        val statement = connection!!.prepareStatement("SELECT * FROM messages WHERE event_id = ?")
        statement.setString(1, eventId.full)
        val rs = statement.executeQuery()
        if (rs.next() == false) return null
        return bot.buildMessageSource(MessageSourceKind.valueOf(rs.getString("kind"))) {
            sender(rs.getLong("sender"))
            target(rs.getLong("target"))
            id(rs.getInt("id"))
            internalId(rs.getInt("internal_id"))
            time(rs.getInt("time"))
        }
    }

    fun save(source: MessageSource, eventId: EventId, kind: MessageSourceKind) {
        val statement =
                connection!!.prepareStatement("INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?)")
        statement.setString(1, kind.toString())
        statement.setLong(2, source.fromId)
        statement.setLong(3, source.targetId)
        statement.setInt(4, source.ids[0])
        statement.setInt(5, source.internalIds[0])
        statement.setInt(6, source.time)
        statement.setString(7, eventId.full)
        statement.executeUpdate()
    }
}
