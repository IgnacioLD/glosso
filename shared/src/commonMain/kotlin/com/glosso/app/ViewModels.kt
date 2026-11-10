package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

/*
 * The ViewModels of the shared UI, each built from the process's [AppServices].
 * They live in the composition's ViewModelStore (the Activity on Android), under
 * the same default key `viewModel()` used, so a rotation keeps the same instance
 * and a screen that is handed a ViewModel and one that asks for it share one.
 */

@Composable
fun lessonViewModel(): LessonViewModel {
    val services = LocalAppServices.current
    return viewModel { LessonViewModel(services) }
}

@Composable
fun storiesViewModel(): StoriesViewModel {
    val services = LocalAppServices.current
    return viewModel { StoriesViewModel(services) }
}

@Composable
fun exercisesViewModel(): ExercisesViewModel {
    val services = LocalAppServices.current
    return viewModel { ExercisesViewModel(services) }
}

@Composable
fun practiceViewModel(): PracticeViewModel {
    val services = LocalAppServices.current
    return viewModel { PracticeViewModel(services) }
}

@Composable
fun onboardingViewModel(): OnboardingViewModel {
    val services = LocalAppServices.current
    return viewModel { OnboardingViewModel(services) }
}

@Composable
fun scoringViewModel(): ScoringViewModel {
    val services = LocalAppServices.current
    return viewModel { ScoringViewModel(services.onDevice) }
}

@Composable
fun profileViewModel(): ProfileViewModel {
    val services = LocalAppServices.current
    return viewModel { ProfileViewModel(services.context) }
}

@Composable
fun subscriptionViewModel(): SubscriptionViewModel {
    val services = LocalAppServices.current
    return viewModel { SubscriptionViewModel(services.subscriptions()) }
}
