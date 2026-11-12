package com.glosso.app

import com.glosso.app.platform.rememberHaptics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Four destinations. Today is the home: one lesson, the weak sounds to review
 * and the honest daily streak. Lessons is the course, You is the learner's
 * record, Library is where the free modes and settings live. Anything else has
 * to earn a place here.
 */
enum class GlossoDestination(
    val labelRes: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    TODAY(Res.string.nav_today, Icons.Outlined.Home, Icons.Filled.Home),
    LESSONS(Res.string.nav_lessons, Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    YOU(Res.string.nav_you, Icons.Outlined.Insights, Icons.Filled.Insights),
    LIBRARY(Res.string.nav_library, Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
}

@Composable
fun GlossoBottomBar(
    current: GlossoDestination,
    onSelect: (GlossoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Column {
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .defaultMinSize(minHeight = Sizes.bottomBar)
                    .padding(top = Space.x8, bottom = Space.x4)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlossoDestination.entries.forEach { destination ->
                    NavItem(
                        destination = destination,
                        selected = current == destination,
                        onClick = { onSelect(destination) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The same four destinations as [GlossoBottomBar], down the side of a wide
 * window, where a bar under a centred column would sit far from the content.
 */
@Composable
fun GlossoNavRail(
    current: GlossoDestination,
    onSelect: (GlossoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(Sizes.navRail)
                .systemBarsPadding()
                .padding(top = Space.x24)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.x16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlossoDestination.entries.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = current == destination,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outline))
    }
}

@Composable
private fun NavItem(
    destination: GlossoDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.fast),
        label = "navColor",
    )
    val pill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (LocalReduceMotion.current) tween(0) else Motion.pulse,
        label = "navPill",
    )
    val reduce = LocalReduceMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.88f else 1f,
        animationSpec = Motion.pulse,
        label = "navScale",
    )
    Column(
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
            ) {
                if (!selected) haptics.tick()
                onClick()
            }
            .padding(vertical = Space.x4),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The selection pill grows out of the icon's centre, the one tinted
        // shape in the bar.
        val indicator = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        Box(
            Modifier
                .size(width = 60.dp, height = 32.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 0.5f + 0.5f * pill
                        alpha = pill.coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(Radius.full))
                    .background(indicator),
            )
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.icon,
                // The label under the icon names the tab; the icon is decoration.
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(Sizes.icon),
            )
        }
        Spacer(Modifier.height(Space.x4))
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
