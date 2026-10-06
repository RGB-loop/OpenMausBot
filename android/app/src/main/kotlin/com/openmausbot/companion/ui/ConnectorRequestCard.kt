package com.openmausbot.companion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.openmausbot.companion.R
import com.openmausbot.companion.core.APIError
import com.openmausbot.companion.core.Chat
import com.openmausbot.companion.core.ConnectorAuthorizationLink
import com.openmausbot.companion.core.ConnectorRequest
import com.openmausbot.companion.core.ConnectorRequestNoComputer
import com.openmausbot.companion.core.ConnectorRequestPolling
import com.openmausbot.companion.core.ConnectorRequestPresentation
import com.openmausbot.companion.core.ConnectorRequestPresentation.Body
import com.openmausbot.companion.core.ConnectorRequestPresentation.Footer
import com.openmausbot.companion.core.ConnectorRequestPresentation.PrimaryAction
import com.openmausbot.companion.core.Message
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in links this run of the app has opened, so "Open again" reopens the
 * same page for its ten minutes instead of starting a new sign-in. Memory
 * only: a link is never written to disk or the transcript.
 */
internal object ConnectorLinkMemory {
    private val links = mutableMapOf<String, ConnectorAuthorizationLink>()

    @Synchronized
    fun link(key: String): URI? {
        val link = links[key] ?: return null
        return link.reusable() ?: run {
            links.remove(key)
            null
        }
    }

    @Synchronized
    fun remember(key: String, url: URI) {
        links[key] = ConnectorAuthorizationLink(url, System.currentTimeMillis())
    }

    @Synchronized
    fun forget(key: String) {
        links.remove(key)
    }
}

/** Why a card's action did not go through, as the card says it. */
internal sealed interface ConnectorCardFailure {
    data object InvalidLink : ConnectorCardFailure
    data object NoBrowser : ConnectorCardFailure
    data object CouldNotOpen : ConnectorCardFailure
    data object NoComputer : ConnectorCardFailure
    data class Computer(val message: String) : ConnectorCardFailure

    companion object {
        fun of(error: Throwable): ConnectorCardFailure = when (error) {
            is ConnectorRequestNoComputer -> NoComputer
            is APIError.BadUrl -> InvalidLink
            else -> Computer(error.message ?: "")
        }
    }
}

/**
 * A bot paused on "Connect to GitHub", as desktop's ConnectorCard draws it.
 * Sign-in happens on the app's own page in the system browser — never in
 * chat — and the card asks the computer whether it finished while that page
 * is open: the computer does not poll on its own, and asking is what flips
 * the card and resumes the bot. Mirrors iOS ConnectorRequestCardView.
 */
@Composable
internal fun ConnectorRequestCardView(chat: Chat, message: Message, request: ConnectorRequest) {
    // The last sync never acts (MOCA-296).
    val canAct = rememberCanAct()
    val presentation = ConnectorRequestPresentation.of(request) ?: return
    val session = LocalCompanion.current.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(message.id) { mutableStateOf(false) }
    var actionGeneration by remember(message.id) { mutableStateOf(0) }
    var failure by remember(message.id) { mutableStateOf<ConnectorCardFailure?>(null) }
    val linkKey = "${chat.threadId}:${message.id}"
    DisposableEffect(linkKey) {
        onDispose { actionGeneration++; busy = false }
    }
    LaunchedEffect(request.dismissed) {
        if (request.dismissed == true) { actionGeneration++; busy = false }
    }

    // While a sign-in page is open: every four seconds, up to five minutes.
    // A patch that moves the status restarts this; leaving the screen ends it.
    // The last sync never acts (MOCA-296): no polling while it is on screen.
    LaunchedEffect(message.id, request.status, request.dismissed, canAct) {
        if (!request.pollsStatus || !canAct) return@LaunchedEffect
        repeat(ConnectorRequestPolling.MAXIMUM_CHECKS) {
            delay(ConnectorRequestPolling.INTERVAL_MILLIS)
            val connected = try {
                session.checkConnectorRequest(chat, message)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
            if (connected) return@LaunchedEffect
        }
    }
    // Back from the browser: one look straight away, not on the next tick.
    LifecycleResumeEffect(message.id, request.status, canAct) {
        if (request.pollsStatus && canAct) {
            scope.launch { runCatching { session.checkConnectorRequest(chat, message) } }
        }
        onPauseOrDispose {}
    }

    fun connect(reuse: Boolean) {
        if (busy) return
        busy = true
        failure = null
        val generation = ++actionGeneration
        scope.launch {
            try {
                val url = (if (reuse) ConnectorLinkMemory.link(linkKey) else null)
                    ?: session.authorizeConnectorRequest(chat, message)
                if (generation != actionGeneration) return@launch
                ConnectorLinkMemory.remember(linkKey, url)
                failure = openInBrowser(context, url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == actionGeneration) failure = ConnectorCardFailure.of(error)
            } finally {
                if (generation == actionGeneration) busy = false
            }
        }
    }

    ConnectorRequestCard(
        request = request,
        presentation = presentation,
        requester = message.from?.name ?: chat.name,
        tint = Color(MausPalette.argb(message.from?.color ?: chat.color)),
        fallbackText = message.text,
        busy = busy,
        canAct = canAct,
        failure = failure,
        onPrimary = { action ->
            when (action) {
                PrimaryAction.CONNECT, PrimaryAction.OPEN_AGAIN -> connect(reuse = true)
                PrimaryAction.TRY_AGAIN -> connect(reuse = false)
                PrimaryAction.CONTINUE_TASK -> if (!busy) {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            session.resumeConnectorRequest(chat, message)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failure = ConnectorCardFailure.of(error)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        },
        onNotNow = {
            actionGeneration++
            busy = false
            failure = null
            ConnectorLinkMemory.forget(linkKey)
            scope.launch {
                try {
                    session.dismissConnectorRequest(chat, message)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failure = ConnectorCardFailure.of(error)
                }
            }
        },
    )
}

/**
 * ACTION_VIEW keeps provider cookies, redirects and account choice in the
 * browser instead of placing them in our process — as Connected Apps does.
 */
private fun openInBrowser(context: Context, url: URI): ConnectorCardFailure? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toASCIIString())))
    null
} catch (_: ActivityNotFoundException) {
    ConnectorCardFailure.NoBrowser
} catch (_: SecurityException) {
    ConnectorCardFailure.CouldNotOpen
}

/** The card itself, with no session: what each state draws. */
@Composable
internal fun ConnectorRequestCard(
    request: ConnectorRequest,
    presentation: ConnectorRequestPresentation,
    requester: String,
    tint: Color,
    fallbackText: String?,
    busy: Boolean,
    canAct: Boolean = true,
    failure: ConnectorCardFailure?,
    onPrimary: (PrimaryAction) -> Unit,
    onNotNow: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .background(secondaryTint.copy(alpha = 0.10f), shape)
            .border(1.dp, if (request.isPending) tint.copy(alpha = 0.55f) else secondaryTint.copy(alpha = 0.18f), shape)
            .testTag("connector-card"),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 14.dp, end = 4.dp, bottom = 14.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(secondaryTint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(request.initial, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        request.displayName,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (presentation.showsConnectedBadge) ConnectedBadge()
                }
                Text(bodyText(presentation.body, request, fallbackText), fontSize = 13.5.sp, color = secondaryTint)
                if (presentation.showsSignInHint) {
                    Text(
                        stringResource(R.string.mobile_connector_card_sign_in_hint),
                        fontSize = 12.sp,
                        color = secondaryTint.copy(alpha = 0.8f),
                    )
                }
                val error = failure?.let { failureText(it) } ?: request.error?.trim()?.takeIf { it.isNotEmpty() }
                if (error != null) {
                    Text(error, fontSize = 12.5.sp, color = Color(MausPalette.argb("red")), modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (presentation.offersNotNow) {
                val notNow = stringResource(R.string.mobile_not_now_e4571490)
                IconButton(onClick = onNotNow, enabled = canAct, modifier = Modifier.testTag("connector-card.not-now")) {
                    Icon(Icons.Filled.Close, contentDescription = notNow, tint = secondaryTint, modifier = Modifier.size(18.dp))
                }
            }
        }
        HorizontalDivider(color = secondaryTint.copy(alpha = 0.15f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(secondaryTint.copy(alpha = 0.04f))
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterStatus(presentation.footer, requester, Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            val primary = presentation.primary
            if (primary != null) {
                Button(
                    onClick = { onPrimary(primary) },
                    enabled = !busy && canAct,
                    colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White),
                    modifier = Modifier.testTag("connector-card.primary"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(primaryLabel(primary), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (presentation.showsContinuing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(MausPalette.argb("green")), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.mobile_connector_card_continuing),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(MausPalette.argb("green")),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedBadge() {
    val green = Color(MausPalette.argb("green"))
    Row(
        modifier = Modifier
            .background(green.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = green, modifier = Modifier.size(11.dp))
        Spacer(Modifier.size(3.dp))
        Text(stringResource(R.string.mobile_connected_c2f9b7b4), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = green)
    }
}

@Composable
private fun FooterStatus(footer: Footer, requester: String, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (footer == Footer.WAITING) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = secondaryTint)
            Spacer(Modifier.size(6.dp))
        }
        Text(
            when (footer) {
                Footer.WAITING -> stringResource(R.string.mobile_connector_card_waiting)
                Footer.READY_TO_USE -> stringResource(R.string.mobile_connector_card_ready)
                Footer.REQUESTED -> stringResource(R.string.mobile_connector_card_requested_by, requester)
            },
            fontSize = 12.sp,
            color = secondaryTint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun bodyText(body: Body, request: ConnectorRequest, fallbackText: String?): String = when (body) {
    Body.DESCRIPTION -> request.description?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackText.orEmpty()
    Body.PAUSED -> stringResource(R.string.mobile_connector_card_paused)
    Body.RESUMED -> stringResource(R.string.mobile_connector_card_resumed)
}

@Composable
private fun primaryLabel(action: PrimaryAction): String = when (action) {
    PrimaryAction.CONNECT -> stringResource(R.string.mobile_connector_card_connect_securely)
    PrimaryAction.TRY_AGAIN -> stringResource(R.string.mobile_try_again_042c862e)
    PrimaryAction.OPEN_AGAIN -> stringResource(R.string.mobile_connector_card_open_again)
    PrimaryAction.CONTINUE_TASK -> stringResource(R.string.mobile_connector_card_continue_task)
}

@Composable
private fun failureText(failure: ConnectorCardFailure): String = when (failure) {
    ConnectorCardFailure.InvalidLink -> stringResource(R.string.mobile_connector_card_invalid_link)
    ConnectorCardFailure.NoBrowser -> stringResource(R.string.mobile_connected_apps_no_browser)
    ConnectorCardFailure.CouldNotOpen -> stringResource(R.string.mobile_connected_apps_auth_page_failed)
    ConnectorCardFailure.NoComputer -> stringResource(R.string.mobile_connector_card_no_computer)
    is ConnectorCardFailure.Computer -> failure.message
}
