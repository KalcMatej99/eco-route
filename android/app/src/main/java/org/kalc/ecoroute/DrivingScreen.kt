package org.kalc.ecoroute

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun DrivingScreen(state: UiState, vm: PlannerViewModel) {
    BackHandler { vm.stopDriving() }
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val alreadyKeptOn = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (!alreadyKeptOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val payload = remember(state.journey, state.selectedRoute, state.selectedPlan, state.activeLegIndex, state.allowanceValue) { state.mapPayloadJson }
    var following by remember { mutableStateOf(true) }
    val driving = state.driving
    val livePayload = JSONObject().put("active", true).put("following", following).apply {
        driving.fix?.let { fix ->
            put("lat", fix.latitude).put("lon", fix.longitude).put("accuracy", fix.accuracyMeters)
            put("bearing", fix.bearingDegrees ?: JSONObject.NULL)
        }
    }.toString()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("driving-screen")) {
        val landscape = maxWidth > maxHeight
        val panelMaxHeight = maxHeight * .7f
        if (landscape) Row(Modifier.fillMaxSize()) {
            RouteMap(payload, Modifier.weight(1f).fillMaxHeight(), drivingPayload = livePayload)
            DrivingReadout(state, vm, following, { following = !following },
                Modifier.width(300.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding())
        } else Column(Modifier.fillMaxSize()) {
            RouteMap(payload, Modifier.weight(1f).fillMaxWidth(), viewportInsets = MapViewportInsets(top = 30.dp), drivingPayload = livePayload)
            DrivingReadout(state, vm, following, { following = !following }, Modifier.fillMaxWidth().heightIn(max = panelMaxHeight).navigationBarsPadding())
        }
    }
}

@Composable
private fun DrivingReadout(state: UiState, vm: PlannerViewModel, following: Boolean, toggleFollow: () -> Unit, modifier: Modifier) {
    val guide = state.driving
    Surface(modifier, tonalElevation = 4.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Driving · section ${state.activeLegIndex + 1}/${state.journey?.legs?.size ?: 1}", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advisory speed", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.Bottom) {
                        // Round down so the large integer never exceeds the mapped ceiling.
                        Text(guide.advisoryKmh?.let { floor(it).toInt().toString() } ?: "—",
                            fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("driving-advisory"))
                        Text(" km/h", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("GPS speed", style = MaterialTheme.typography.labelMedium)
                    Text(guide.fix?.speedKmh?.roundToInt()?.toString() ?: "—", fontSize = 28.sp,
                        modifier = Modifier.testTag("driving-actual-speed"))
                    Text("km/h", style = MaterialTheme.typography.labelSmall)
                    Text("Mapped limit: ${guide.mappedLimitKmh?.let { floor(it).toInt().toString() } ?: if (guide.mappedLimitUnlimited) "Unlimited" else "Unknown"}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(guide.message, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("driving-status"))
            if (guide.roadName.isNotBlank()) Text("${guide.roadName} · ${guide.basis}", style = MaterialTheme.typography.bodySmall)
            guide.remainingMeters?.let { Text(String.format(Locale.UK, "%.1f km remaining in section", it / 1000), style = MaterialTheme.typography.bodySmall) }
            Text("Follow road signs and conditions. This is a modeled estimate; it does not detect traffic or temporary limits.", style = MaterialTheme.typography.bodySmall)
            if (guide.arrived && state.activeLegIndex < (state.journey?.legs?.lastIndex ?: 0)) {
                Button(vm::continueDriving, Modifier.fillMaxWidth().testTag("continue-driving")) { Text("Continue to next section") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(toggleFollow, Modifier.weight(1f).testTag("driving-follow")) { Text(if (following) "Explore map" else "Follow me") }
                Button(vm::stopDriving, Modifier.weight(1f).testTag("stop-driving")) {
                    Icon(Icons.Default.Stop, null); Text("Stop")
                }
            }
        }
    }
}
