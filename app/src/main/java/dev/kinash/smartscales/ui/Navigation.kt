package dev.kinash.smartscales.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kinash.smartscales.ui.history.HistoryScreen
import dev.kinash.smartscales.ui.inbox.InboxScreen
import dev.kinash.smartscales.ui.measurement.MeasurementEditScreen
import dev.kinash.smartscales.ui.profile.ProfileEditScreen
import kotlinx.serialization.Serializable

@Serializable
data object HistoryRoute

@Serializable
data object InboxRoute

/** A new profile when [profileId] is null. */
@Serializable
data class ProfileEditRoute(val profileId: Long? = null)

/** A new manual measurement for [profileId] when [measurementId] is null. */
@Serializable
data class MeasurementEditRoute(val measurementId: Long? = null, val profileId: Long? = null)

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
            )
        }
        composable<InboxRoute> {
            InboxScreen(
                onBack = back,
                onOpenMeasurement = { navController.navigate(MeasurementEditRoute(measurementId = it)) },
            )
        }
        composable<ProfileEditRoute> { ProfileEditScreen(onDone = back) }
        composable<MeasurementEditRoute> { MeasurementEditScreen(onDone = back) }
    }
}
