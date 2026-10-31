package dev.kinash.smartscales.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kinash.smartscales.ui.history.HistoryScreen
import dev.kinash.smartscales.ui.inbox.InboxScreen
import dev.kinash.smartscales.ui.measurement.MeasurementEditScreen
import dev.kinash.smartscales.ui.profile.ProfileEditScreen
import dev.kinash.smartscales.ui.scale.ScaleEditScreen
import dev.kinash.smartscales.ui.scale.ScaleSearchScreen
import dev.kinash.smartscales.ui.scale.ScalesScreen
import dev.kinash.smartscales.ui.weighing.WeighingScreen
import kotlinx.serialization.Serializable

@Serializable
data object HistoryRoute

@Serializable
data object InboxRoute

/** A new profile when [profileId] is null; a new one gets the inbox weighing [assignMeasurementId], if given. */
@Serializable
data class ProfileEditRoute(val profileId: Long? = null, val assignMeasurementId: Long? = null)

/** A new manual measurement for [profileId] when [measurementId] is null. */
@Serializable
data class MeasurementEditRoute(val measurementId: Long? = null, val profileId: Long? = null)

@Serializable
data object WeighingRoute

@Serializable
data object ScalesRoute

@Serializable
data object ScaleSearchRoute

@Serializable
data class ScaleEditRoute(val id: Long)

@Composable
fun SmartScalesNavHost() {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController, startDestination = HistoryRoute) {
        composable<HistoryRoute> {
            HistoryScreen(
                onAddProfile = { navController.navigate(ProfileEditRoute()) },
                onEditProfile = { navController.navigate(ProfileEditRoute(it)) },
                onAddMeasurement = { navController.navigate(MeasurementEditRoute(profileId = it)) },
                onOpenMeasurement = { navController.navigate(MeasurementEditRoute(measurementId = it)) },
                onOpenInbox = { navController.navigate(InboxRoute) },
                onWeigh = { navController.navigate(WeighingRoute) },
            )
        }
        composable<WeighingRoute> {
            WeighingScreen(
                onBack = back,
                onOpenScales = { navController.navigate(ScalesRoute) },
                onAddScale = { navController.navigate(ScaleSearchRoute) },
                onOpenScale = { navController.navigate(ScaleEditRoute(it)) },
                onCreateProfile = { navController.navigate(ProfileEditRoute(assignMeasurementId = it)) },
            )
        }
        composable<ScalesRoute> {
            ScalesScreen(
                onBack = back,
                onAddScale = { navController.navigate(ScaleSearchRoute) },
                onOpenScale = { navController.navigate(ScaleEditRoute(it)) },
            )
        }
        composable<ScaleSearchRoute> { ScaleSearchScreen(onDone = back) }
        composable<ScaleEditRoute> { ScaleEditScreen(onDone = back) }
        composable<InboxRoute> {
            InboxScreen(
                onBack = back,
                onOpenMeasurement = { navController.navigate(MeasurementEditRoute(measurementId = it)) },
                onCreateProfile = { navController.navigate(ProfileEditRoute(assignMeasurementId = it)) },
            )
        }
        composable<ProfileEditRoute> { ProfileEditScreen(onDone = back) }
        composable<MeasurementEditRoute> { MeasurementEditScreen(onDone = back) }
    }
}
