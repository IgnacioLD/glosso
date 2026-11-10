package com.glosso.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.LessonNote
import com.glosso.engine.model.NoteExample
import com.glosso.engine.model.NoteKind
import org.jetbrains.compose.resources.stringResource

/**
 * The lesson's first step when it carries notes: how the situation works in
 * the culture of the language, read before the base. Each note is a short
 * editorial section (what it is about, a title, a few lines) with the phrases
 * that show it; a phrase that is not said is marked as such, calmly, never as
 * an error the learner made.
 */
@Composable
internal fun ContextStep(notes: List<LessonNote>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollEdgeFade(scrollState)
            .padding(horizontal = Space.gutter)
            .verticalScroll(scrollState),
    ) {
        notes.forEachIndexed { index, note ->
            if (index > 0) {
                Spacer(Modifier.height(Space.x24))
                Hairline()
                Spacer(Modifier.height(Space.x24))
            }
            ContextNote(note)
        }
        Spacer(Modifier.height(Space.x24))
    }
}

@Composable
private fun ContextNote(note: LessonNote) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(noteKindLabel(note.kind))
        Spacer(Modifier.height(Space.x4))
        Text(
            text = note.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.x8))
        Text(
            text = note.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (note.examples.isNotEmpty()) {
            Spacer(Modifier.height(Space.x16))
            Column(verticalArrangement = Arrangement.spacedBy(Space.x8)) {
                note.examples.forEach { NoteExampleLine(it) }
            }
        }
    }
}

/**
 * One phrase of a note, in the language taught with its stress marks (a
 * reading aid, kept as written), and its meaning in the interface language.
 * Its meaning is always shown: here it explains, it is not a quiz.
 */
@Composable
private fun NoteExampleLine(example: NoteExample) {
    val translation = uiTranslation(example.translations)
    val wrong = example.wrong
    Surface(
        color = if (wrong) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        border = if (wrong) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Space.x16, vertical = Space.x12)) {
            if (wrong) {
                Text(
                    text = stringResource(Res.string.note_example_wrong),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x4))
            }
            Text(
                text = example.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (wrong) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (wrong) TextDecoration.LineThrough else null,
            )
            if (translation != null) {
                Spacer(Modifier.height(Space.x2))
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun noteKindLabel(kind: NoteKind): String = stringResource(
    when (kind) {
        NoteKind.CULTURE -> Res.string.note_kind_culture
        NoteKind.ETIQUETTE -> Res.string.note_kind_etiquette
        NoteKind.REGISTER -> Res.string.note_kind_register
        NoteKind.LANGUAGE -> Res.string.note_kind_language
    },
)
