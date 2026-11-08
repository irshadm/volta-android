package dev.irshadm.volta.core.maps

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleMapsKeyCheckerTest {
    private val server = MockWebServer()
    private val identity = AppIdentity("dev.example.app", "0A1B2C")
    private var savedKey: String? = null

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun api() = GoogleApi(OkHttpClient(), identity) { savedKey }
    private fun checker() = GoogleMapsKeyChecker(
        api(),
        placesBase = server.url("/places").toString(),
        routesBase = server.url("/routes").toString(),
        elevationBase = server.url("/elevation/json").toString(),
    )
    private fun elevationOk() = ok("""{"results":[{"elevation":920.1}],"status":"OK"}""")
    private fun ok(body: String = "{}") = MockResponse.Builder().body(body).build()
    private fun denied(reason: String) = MockResponse.Builder().code(403)
        .body("""{"error":{"code":403,"message":"denied","status":"PERMISSION_DENIED","details":[{"reason":"$reason"}]}}""").build()

    @Test fun `every API answering means the key passes`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        server.enqueue(elevationOk())
        val report = checker().check("  KEY123  ")
        assertTrue(report.passed)

        val places = server.takeRequest()
        assertEquals("/places/places:searchText", places.url.encodedPath)
        assertEquals("KEY123", places.headers["X-Goog-Api-Key"])
        assertEquals("places.id", places.headers["X-Goog-FieldMask"])
        assertEquals("dev.example.app", places.headers["X-Android-Package"])
        assertEquals("0A1B2C", places.headers["X-Android-Cert"])
        assertEquals("/routes/directions/v2:computeRoutes", server.takeRequest().url.encodedPath)
        val elevation = server.takeRequest()
        assertEquals("/elevation/json", elevation.url.encodedPath)
        assertEquals("KEY123", elevation.url.queryParameter("key"))
        assertEquals("dev.example.app", elevation.headers["X-Android-Package"])
    }

    @Test fun `Elevation refusing with HTTP 200 still fails the key`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        server.enqueue(ok("""{"error_message":"This API is not activated on your API project.","results":[],"status":"REQUEST_DENIED"}"""))
        val report = checker().check("KEY123")
        val (api, failure) = report.firstFailure!!
        assertEquals(MapsApi.Elevation, api)
        assertEquals(KeyProblem.ApiNotEnabled, failure.problem)
    }

    @Test fun `a refused API is reported with its problem`() = runTest {
        server.enqueue(ok())
        server.enqueue(denied("SERVICE_DISABLED"))
        server.enqueue(elevationOk())
        val report = checker().check("KEY123")
        assertFalse(report.passed)
        val (api, failure) = report.firstFailure!!
        assertEquals(MapsApi.Routes, api)
        assertEquals(KeyProblem.ApiNotEnabled, failure.problem)
    }

    @Test fun `no server means a network problem, not a bad key`() = runTest {
        server.close()
        val report = checker().check("KEY123")
        MapsApi.entries.forEach { assertEquals(KeyProblem.Network, (report.results[it] as ApiCheck.Failed).problem) }
    }

    @Test fun `normal calls read the saved key each time`() = runTest {
        server.enqueue(ok("""{"a":1}"""))
        server.enqueue(ok())
        savedKey = "FIRST"
        assertEquals("""{"a":1}""", api().post(server.url("/x").toString(), "{}", "f"))
        savedKey = "SECOND"
        api().post(server.url("/x").toString(), "{}", "f")
        assertEquals("FIRST", server.takeRequest().headers["X-Goog-Api-Key"])
        assertEquals("SECOND", server.takeRequest().headers["X-Goog-Api-Key"])
    }

    @Test fun `no saved key fails before any request`() = runTest {
        savedKey = null
        assertFailsWith<MissingMapsKeyException> { api().post(server.url("/x").toString(), "{}", "f") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `key format check`() {
        assertTrue(MapsKeyFormat.looksValid("AIzaSyB0000000000000000000000000000w4Qk"))
        assertFalse(MapsKeyFormat.looksValid("AIza-too-short"))
        assertFalse(MapsKeyFormat.looksValid("BIzaSyB0000000000000000000000000000w4Qk"))
    }
}
