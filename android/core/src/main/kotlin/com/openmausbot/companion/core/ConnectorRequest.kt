package com.openmausbot.companion.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A bot's in-chat request to connect an app — "Connect to GitHub" — and the
 * small decisions the phone's card makes about it.
 *
 * The computer stores the request as a transcript message of kind
 * "connector" (shared/wire.ts `ConnectorCardData`) and patches it as the
 * person signs in. Desktop draws it as src/components/ConnectorCard.tsx; the
 * phone's card mirrors its states and buttons, which is why the decisions
 * live here, testable without a view. Mirrors iOS ConnectorRequest.swift.
 *
 * Not the Connected Apps catalog entry in Settings ([ConnectorCard]): that
 * lists an app anyone could connect; this is one bot, paused in one thread,
 * waiting for one app.
 */
@Serializable
data class ConnectorRequest(
    /** The Composio toolkit slug. Never sent back: actions go by message id. */
    val slug: String? = null,
    val label: String? = null,
    val description: String? = null,
    val status: Status? = null,
    /** Cards made by one bot request resume together once all connect. */
    val resumeKey: String? = null,
    /** The account name, when the bot asked for a second account. */
    val alias: String? = null,
    val error: String? = null,
    val dismissed: Boolean? = null,
    val resumed: Boolean? = null,
) {
    /** A state from a newer computer decodes as [UNKNOWN] rather than failing the thread. */
    @Serializable(with = ConnectorRequestStatusSerializer::class)
    enum class Status { REQUIRED, AUTHORIZING, CONNECTED, FAILED, UNKNOWN }

    /** What the card calls the app: the computer's label, else its slug. */
    val displayName: String
        get() = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: slug?.trim()?.takeIf { it.isNotEmpty() }
            ?: "App"

    /** The square badge: the name's first letter, as desktop draws it. */
    val initial: String get() = displayName.take(1).uppercase()

    /** Still waiting on the person: not connected and not set aside. */
    val isPending: Boolean get() = dismissed != true && status != Status.CONNECTED

    /**
     * Ask the computer for the sign-in result only while a sign-in page is
     * open. The computer has no poller of its own: a status check is what
     * flips the card to connected and resumes the bot's turn.
     */
    val pollsStatus: Boolean get() = dismissed != true && status == Status.AUTHORIZING
}

object ConnectorRequestStatusSerializer : KSerializer<ConnectorRequest.Status> {
    override val descriptor = PrimitiveSerialDescriptor("ConnectorRequest.Status", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ConnectorRequest.Status = when (decoder.decodeString()) {
        "required" -> ConnectorRequest.Status.REQUIRED
        "authorizing" -> ConnectorRequest.Status.AUTHORIZING
        "connected" -> ConnectorRequest.Status.CONNECTED
        "failed" -> ConnectorRequest.Status.FAILED
        else -> ConnectorRequest.Status.UNKNOWN
    }

    override fun serialize(encoder: Encoder, value: ConnectorRequest.Status) {
        encoder.encodeString(value.name.lowercase())
    }
}

/** The card's states, as desktop's ConnectorCard draws them. */
data class ConnectorRequestPresentation(
    val primary: PrimaryAction?,
    /** The "Not now" control, which dismisses the request. */
    val offersNotNow: Boolean,
    val footer: Footer,
    val body: Body,
    /** "Sign in or enter the app key on the secure connection page — never in chat." */
    val showsSignInHint: Boolean,
    /** The "Connected" pill beside the name. */
    val showsConnectedBadge: Boolean,
    /** The quiet "Continuing" receipt where the button was. */
    val showsContinuing: Boolean,
) {
    enum class PrimaryAction { CONNECT, TRY_AGAIN, OPEN_AGAIN, CONTINUE_TASK }

    /** "Requested by …", "Waiting for sign-in…" with a spinner, or "Ready to use". */
    enum class Footer { REQUESTED, WAITING, READY_TO_USE }

    /** The computer's description, or one of the two connected lines. */
    enum class Body { DESCRIPTION, PAUSED, RESUMED }

    companion object {
        /** null when the card is not drawn: desktop hides a request the person set aside. */
        fun of(request: ConnectorRequest): ConnectorRequestPresentation? {
            if (request.dismissed == true) return null
            return when (request.status ?: ConnectorRequest.Status.REQUIRED) {
                ConnectorRequest.Status.CONNECTED -> {
                    val resumed = request.resumed == true
                    ConnectorRequestPresentation(
                        primary = if (resumed) null else PrimaryAction.CONTINUE_TASK,
                        offersNotNow = false,
                        footer = Footer.READY_TO_USE,
                        body = if (resumed) Body.RESUMED else Body.PAUSED,
                        showsSignInHint = false,
                        showsConnectedBadge = true,
                        showsContinuing = resumed,
                    )
                }
                ConnectorRequest.Status.AUTHORIZING -> pending(PrimaryAction.OPEN_AGAIN, Footer.WAITING)
                ConnectorRequest.Status.FAILED -> pending(PrimaryAction.TRY_AGAIN, Footer.REQUESTED)
                ConnectorRequest.Status.REQUIRED -> pending(PrimaryAction.CONNECT, Footer.REQUESTED)
                // A state this build cannot act on: say what the bot wants and
                // offer nothing that might do the wrong thing.
                ConnectorRequest.Status.UNKNOWN -> ConnectorRequestPresentation(
                    primary = null, offersNotNow = false, footer = Footer.REQUESTED, body = Body.DESCRIPTION,
                    showsSignInHint = false, showsConnectedBadge = false, showsContinuing = false,
                )
            }
        }

        private fun pending(primary: PrimaryAction, footer: Footer) = ConnectorRequestPresentation(
            primary = primary, offersNotNow = true, footer = footer, body = Body.DESCRIPTION,
            showsSignInHint = true, showsConnectedBadge = false, showsContinuing = false,
        )
    }
}

/**
 * The four routes a card acts through, each scoped to the bot that asked and
 * the message it asked in. The companion's allowlist admits exactly these.
 */
enum class ConnectorRequestAction(val wire: String) {
    AUTHORIZE("authorize"), STATUS("status"), RESUME("resume"), DISMISS("dismiss");

    val method: String get() = if (this == STATUS) "GET" else "POST"

    /**
     * `/api/bots/:botId/connector-cards/:messageId/:action`, or null when an
     * id is not one the computer's `[\w-]+` route would match.
     */
    fun path(botId: String, messageId: String): String? {
        if (!validId(botId) || !validId(messageId)) return null
        return "/api/bots/$botId/connector-cards/$messageId/$wire"
    }

    companion object {
        /** JavaScript `\w` is ASCII: letters, digits and underscore, plus hyphen. */
        internal fun validId(value: String): Boolean =
            value.isNotEmpty() && value.length <= 200 && value.all {
                it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '-'
            }
    }
}

/** A card's call never left the phone: there is no paired computer to send it to. */
class ConnectorRequestNoComputer : Exception("No computer connected")

/** What a status check said. */
@Serializable
data class ConnectorRequestStatus(
    val connected: Boolean = false,
    val pending: Boolean? = null,
    val status: String? = null,
)

/** Every four seconds, 75 times — five minutes, desktop's numbers. */
object ConnectorRequestPolling {
    const val INTERVAL_MILLIS = 4_000L
    const val MAXIMUM_CHECKS = 75
}

/**
 * The sign-in page the card last opened. Composio links expire after ten
 * minutes and reopening the same page must not restart that clock, so the
 * card reuses it. Memory only — never the transcript or disk.
 */
data class ConnectorAuthorizationLink(val url: java.net.URI, val createdAtMillis: Long) {
    fun reusable(nowMillis: Long = System.currentTimeMillis()): java.net.URI? {
        val age = nowMillis - createdAtMillis
        return if (age in 0 until LIFETIME_MILLIS) url else null
    }

    companion object {
        const val LIFETIME_MILLIS = 10 * 60 * 1_000L
    }
}

/**
 * The bot a connection card acts for: the chat's bot, or in a room the member
 * that asked. null when a room card names no member — guessing one would act
 * for the wrong bot.
 */
fun Chat.connectorOwner(message: Message): String? = when (this) {
    is Chat.BotChat -> bot.id
    is Chat.RoomChat -> message.from?.botId
}

/**
 * The line a roster row uses for a connection card: a request
 * still waiting reads as the computer's own sentence ("Connect GitHub to
 * continue."), a settled one as the app it was about.
 */
internal val Message.connectorPreviewLine: String
    get() {
        val request = connector ?: return text.orEmpty()
        val line = text
        return if (request.isPending && !line.isNullOrEmpty()) line else request.displayName
    }
