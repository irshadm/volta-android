package dev.irshadm.volta.core.maps

/** One Google API the app depends on, as shown in the key check list. */
enum class MapsApi(val label: String) {
    Places("Places API (New)"),
    Routes("Routes API"),
    Elevation("Elevation API"),
}

/** The outcome for one API. */
sealed interface ApiCheck {
    data object Passed : ApiCheck
    data class Failed(val problem: KeyProblem, val googleMessage: String) : ApiCheck
}

/** The outcome of checking a key against every API in [MapsApi], in that order. */
data class KeyCheckReport(val results: Map<MapsApi, ApiCheck>) {
    val passed: Boolean get() = results.values.all { it == ApiCheck.Passed }
    val firstFailure: Pair<MapsApi, ApiCheck.Failed>? get() =
        results.entries.firstNotNullOfOrNull { (api, check) -> (check as? ApiCheck.Failed)?.let { api to it } }
}

/** Tries a key against Google before the app saves it. */
fun interface MapsKeyChecker {
    suspend fun check(key: String): KeyCheckReport
}

/**
 * Checks a key with the cheapest real request each API accepts:
 * - Places: a Text Search asking only for place IDs (the free "IDs only" field mask).
 * - Routes: a two-point route asking only for the distance.
 * - Elevation: the height of one point, for the hills along a route.
 * All are real calls, so they catch every problem a trip plan would hit: disabled APIs, API restrictions,
 * app restrictions and billing.
 */
class GoogleMapsKeyChecker(
    private val api: GoogleApi,
    private val placesBase: String = "https://places.googleapis.com/v1",
    private val routesBase: String = "https://routes.googleapis.com",
    private val elevationBase: String = "https://maps.googleapis.com/maps/api/elevation/json",
) : MapsKeyChecker {

    override suspend fun check(key: String): KeyCheckReport {
        val trimmed = key.trim()
        return KeyCheckReport(
            linkedMapOf(
                MapsApi.Places to attempt { api.post("$placesBase/places:searchText", PLACES_PROBE, "places.id", trimmed) },
                MapsApi.Routes to attempt { api.post("$routesBase/directions/v2:computeRoutes", ROUTES_PROBE, "routes.distanceMeters", trimmed) },
                MapsApi.Elevation to attempt { api.getLegacy("$elevationBase?locations=12.9716,77.5946", trimmed) },
            ),
        )
    }

    private suspend fun attempt(call: suspend () -> String): ApiCheck = try {
        call()
        ApiCheck.Passed
    } catch (e: GoogleApiException) {
        ApiCheck.Failed(e.problem, e.googleMessage)
    }

    private companion object {
        const val PLACES_PROBE = """{"textQuery":"EV charging station","pageSize":1}"""

        // Two points a few hundred metres apart; any reachable pair works.
        const val ROUTES_PROBE = """{"origin":{"location":{"latLng":{"latitude":12.9716,"longitude":77.5946}}},""" +
            """"destination":{"location":{"latLng":{"latitude":12.9750,"longitude":77.5990}}},"travelMode":"DRIVE"}"""
    }
}

/** Basic shape check before spending a request: Google keys are 39 characters starting with "AIza". */
object MapsKeyFormat {
    private val pattern = Regex("^AIza[0-9A-Za-z_-]{35}$")
    fun looksValid(key: String): Boolean = pattern.matches(key.trim())
}
