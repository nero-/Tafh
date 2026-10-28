package dev.nero.tafh.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nero.tafh.service.terminal.SessionStatus
import dev.nero.tafh.service.terminal.TabSession
import dev.nero.tafh.ui.components.ChuButton
import dev.nero.tafh.ui.components.ChuButtonVariant
import dev.nero.tafh.ui.components.ChuText
import dev.nero.tafh.ui.components.minimumInteractiveComponentSize
import dev.nero.tafh.ui.theme.ChuColors
import dev.nero.tafh.ui.theme.ChuTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

// Approximates the visible title width before the fixed-size tab ellipsizes it.
private const val TAB_TITLE_COLLISION_PREFIX_LENGTH = 14

/** Human-readable status text for accessibility. */
internal fun statusLabel(status: SessionStatus): String =
    when (status) {
        SessionStatus.Connected -> "connected"
        SessionStatus.Connecting -> "connecting"
        SessionStatus.Reconnecting -> "reconnecting"
        SessionStatus.Disconnected -> "disconnected"
        SessionStatus.Error -> "error"
    }

/**
 * Always-visible global top tab strip for strip mode.
 *
 * Shows all active terminal sessions in a compact top strip. The tab list scrolls; primary actions
 * stay pinned on the right.
 */
@Composable
fun TerminalTabStrip(
    tabs: List<TabSession>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onAddTab: () -> Unit,
    onOpenManager: () -> Unit,
    newTabPicker: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val scrollState = rememberScrollState()
    // Both trailing actions keep a 48dp touch target (48 + 4 + 48) even though they draw 32dp.
    val trailingActionWidth = if (tabs.isNotEmpty()) 100.dp else 48.dp
    val tabOffsets = remember { mutableStateMapOf<String, Int>() }
    val rowRootLeft = remember { mutableStateOf(0) }
    val titles =
        tabs.map { tab ->
            val title by
                remember(tab) { tab.sessionState.map { it.title?.takeIf(String::isNotBlank) } }
                    .collectAsStateWithLifecycle(initialValue = null)
            title
        }
    val titlePrefixCounts =
        titles.filterNotNull().groupingBy { it.take(TAB_TITLE_COLLISION_PREFIX_LENGTH) }.eachCount()

    val activeTabOffset = activeTabId?.let { tabOffsets[it] }

    LaunchedEffect(activeTabId, activeTabOffset) {
        val target = activeTabOffset ?: return@LaunchedEffect
        scrollState.animateScrollTo(target)
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant)
                // No vertical padding: the 48dp-tall tab/action touch targets define the strip's
                // height, and their invisible 8dp of padding above/below each 32dp drawn box keeps
                // the same breathing room (48 = 8 + 32 + 8).
                .padding(start = 4.dp, end = 6.dp)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(end = trailingActionWidth)
                    .horizontalScroll(scrollState)
                    .onGloballyPositioned { coords ->
                        rowRootLeft.value = coords.localToRoot(Offset.Zero).x.roundToInt()
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isActive = tab.id == activeTabId
                val alias = terminalTabDisplayLabel(tab)
                val title = titles[index]
                val label =
                    when {
                        title == null -> alias
                        titlePrefixCounts.getValue(title.take(TAB_TITLE_COLLISION_PREFIX_LENGTH)) ==
                            1 -> title
                        else -> "$alias · $title"
                    }

                Box(
                    modifier =
                        Modifier.semantics { contentDescription = label }
                            .clickable { onTabSelected(tab.id) }
                            // 48dp touch target; the drawn tab stays 32dp tall inside it.
                            .minimumInteractiveComponentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.onGloballyPositioned { coords ->
                                    tabOffsets[tab.id] =
                                        coords.localToRoot(Offset.Zero).x.roundToInt() -
                                            rowRootLeft.value + scrollState.value
                                }
                                .defaultMinSize(minHeight = 32.dp)
                                .widthIn(min = 44.dp, max = 160.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(
                                    if (isActive) colors.accent.copy(alpha = 0.16f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChuText(
                            text = label,
                            style = typography.labelSmall,
                            color = if (isActive) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                            Box(
                                modifier =
                                    Modifier.align(Alignment.BottomCenter)
                                        .padding(start = 2.dp, end = 2.dp, bottom = 1.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.accent)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (tabs.isNotEmpty()) {
                ChuButton(
                    onClick = onOpenManager,
                    // 48dp touch target around the 32dp drawn ghost button.
                    modifier = Modifier.defaultMinSize(minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    minHeight = 32.dp,
                    contentDescription = "all tabs",
                ) {
                    ChuText(
                        if (tabs.size > 99) "≡" else "${tabs.size}",
                        style = typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }

            Box {
                ChuButton(
                    onClick = onAddTab,
                    // 48dp touch target around the 32dp drawn ghost button.
                    modifier = Modifier.defaultMinSize(minWidth = 32.dp),
                    variant = ChuButtonVariant.Ghost,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    minHeight = 32.dp,
                    contentDescription = "new connection",
                ) {
                    ChuText("+", style = typography.label, color = colors.accent)
                }
                newTabPicker()
            }
        }
    }
}
