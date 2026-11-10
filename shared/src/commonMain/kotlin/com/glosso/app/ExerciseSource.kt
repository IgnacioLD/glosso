package com.glosso.app

import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel

/**
 * The base an exercise is built on. Per the lesson design every exercise answers
 * from a piece of real content: a story, a news article or a dialogue. In the
 * exercise library that base is not on screen, so a comprehension task ("What is
 * the new terminal for?") cannot be answered from the task alone. The library
 * names the base and offers to read it first, instead of dropping the learner
 * into a loose question with no context.
 */
sealed interface ExerciseSource {
    val title: String

    /** A story that the app can open on its own (the reader). */
    data class Story(val story: StoryModel) : ExerciseSource {
        override val title: String get() = story.title
    }

    /**
     * A lesson, whose first step reads the base (story, article or dialogue).
     * Articles and dialogues have no reader of their own, so the lesson is the
     * only way to reach them.
     */
    data class Lesson(val lesson: LessonModel) : ExerciseSource {
        override val title: String get() = lesson.title
    }
}

/**
 * Maps each exercise to the base it belongs to, using only references the
 * content already carries:
 *
 * - the exercise's own `storyId`, or
 * - the lesson whose `exerciseIds` lists it; the lesson holds the base, be it a
 *   story, an article or a dialogue.
 *
 * A story is preferred when both apply: it opens on its own, without the rest of
 * the lesson. An exercise with no reference at all is left without a source
 * rather than guessed at; whether it needs context the content should say, and
 * today it does not (see docs/LIBRARY-EXERCISE-CONTEXT.md).
 */
fun exerciseSources(
    exercises: List<ExerciseModel>,
    lessons: List<LessonModel>,
    stories: List<StoryModel>,
): Map<String, ExerciseSource> {
    val storyById = stories.associateBy { it.id }
    val lessonByExercise = HashMap<String, LessonModel>()
    for (lesson in lessons) {
        for (exerciseId in lesson.exerciseIds) {
            lessonByExercise.getOrPut(exerciseId) { lesson }
        }
    }
    val sources = HashMap<String, ExerciseSource>()
    for (exercise in exercises) {
        val story = exercise.storyId?.let { storyById[it] }
        if (story != null) {
            sources[exercise.id] = ExerciseSource.Story(story)
            continue
        }
        lessonByExercise[exercise.id]?.let { sources[exercise.id] = ExerciseSource.Lesson(it) }
    }
    return sources
}
