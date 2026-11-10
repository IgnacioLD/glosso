package com.glosso.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ExercisesScreen(
    vm: ExercisesViewModel = exercisesViewModel(),
    onOpenSource: (ExerciseSource) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var theme by rememberSaveable { mutableStateOf<String?>(null) }
    val themes = remember(state.exercises) { state.exercises.map { it.theme }.distinct().sorted() }
    val visible = theme?.let { t -> state.exercises.filter { it.theme == t } } ?: state.exercises

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(title = stringResource(Res.string.exercises_title), onBack = onBack)

        when {
            state.loading && state.exercises.isEmpty() ->
                LoadingState(Modifier.weight(1f))

            state.error != null && state.exercises.isEmpty() ->
                ErrorState(onRetry = { vm.load() }, modifier = Modifier.weight(1f), detail = state.error)

            state.exercises.isEmpty() ->
                EmptyState(
                    title = stringResource(Res.string.exercises_empty_title),
                    message = stringResource(Res.string.exercises_empty_message),
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Quiz,
                )

            else -> {
                if (themes.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Space.gutter, vertical = Space.x8),
                        horizontalArrangement = Arrangement.spacedBy(Space.x8),
                    ) {
                        QuietTab(stringResource(Res.string.exercises_all), theme == null) { theme = null }
                        themes.forEach { name ->
                            QuietTab(themeLabel(name, state.themeLabels), theme == name) { theme = name }
                        }
                    }
                    Spacer(Modifier.height(Space.x8))
                }
                if (visible.isNotEmpty()) {
                    ExerciseRunner(
                        exercises = visible,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        sourceOf = vm::sourceOf,
                        onOpenSource = onOpenSource,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuietTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(Motion.fast),
        label = "tabContainer",
    )
    Box(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.full))
                .background(container)
                .padding(horizontal = Space.x16, vertical = Space.x8),
        )
    }
}
