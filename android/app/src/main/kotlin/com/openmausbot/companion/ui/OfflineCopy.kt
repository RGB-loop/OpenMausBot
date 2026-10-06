package com.openmausbot.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmausbot.companion.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone showing its saved copy of the computer (MOCA-296): what the
 * banner says, and the one switch every write affordance reads.
 */
object OfflineCopyRules {
    /** "9:41" for a copy saved today, a date and time otherwise. */
    fun lastUpdated(
        savedAtMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val saved = Instant.ofEpochMilli(savedAtMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val style = if (saved.toLocalDate() == now.toLocalDate()) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        }
        return saved.format(style.withLocale(locale))
    }
}

/**
 * Whether anything on screen may be sent, answered, stopped or changed: false
 * while the phone shows its saved copy. Session refuses those writes anyway;
 * this is so the screen does not offer them.
 */
@Composable
fun rememberCanAct(): Boolean {
    val session = LocalCompanion.current.session
    val flow = remember(session) { session.state.map { it.canAct }.distinctUntilChanged() }
    val canAct by flow.collectAsState(initial = session.state.value.canAct)
    return canAct
}

/**
 * "Not connected · last updated 9:41", at the top of Home and of every chat
 * while the saved copy is on screen. Tapping it tries the computer again.
 * Draws nothing once a live hydrate has replaced the copy.
 */
@Composable
fun OfflineCopyBanner(modifier: Modifier = Modifier) {
    val session = LocalCompanion.current.session
    val savedAtFlow = remember(session) { session.state.map { it.cachedAt }.distinctUntilChanged() }
    val savedAt by savedAtFlow.collectAsState(initial = session.state.value.cachedAt)
    val scope = rememberCoroutineScope()
    val stamp = savedAt ?: return
    val text = stringResource(
        R.string.mobile_offline_banner,
        OfflineCopyRules.lastUpdated(stamp, System.currentTimeMillis()),
    )
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color(MausPalette.argb("orange")),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(secondaryTint.copy(alpha = 0.12f), CircleShape)
                .clickable(role = Role.Button) { scope.launch { session.refresh() } }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Where the composer would be while the copy is on screen: nothing to type into, and why. */
@Composable
fun OfflineComposer(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)
            .background(secondaryTint.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.mobile_offline_reconnect_to_send),
            fontSize = 15.sp,
            color = secondaryTint,
        )
    }
}

/** The line an ask shows instead of its buttons while the copy is on screen. */
@Composable
fun ReconnectToAnswer(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.mobile_offline_reconnect_to_answer),
        fontSize = 13.sp,
        color = secondaryTint,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Under a screen's header while the copy is on screen: why its actions are gone. */
@Composable
fun ReconnectToChange(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.mobile_offline_reconnect_to_change),
        fontSize = 13.sp,
        color = secondaryTint,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
