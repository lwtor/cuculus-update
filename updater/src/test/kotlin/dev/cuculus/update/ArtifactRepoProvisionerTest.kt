package dev.cuculus.update

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactRepoProvisionerTest {

    @Test
    fun `returns AlreadyExists and performs no write when the repo is present`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"full_name":"lwtor/cuculus-artifacts"}""").build())
            val base = server.url("/").toString().removeSuffix("/")

            val result = ArtifactRepoProvisioner.ensureRepository(
                OkHttpClient(), TOKEN, "lwtor", "cuculus-artifacts", apiBaseUrl = base,
            )

            assertTrue(result is ArtifactRepoProvisioner.Result.AlreadyExists)
            assertEquals(1, server.requestCount)
            val probe = server.takeRequest()
            assertEquals("/repos/lwtor/cuculus-artifacts", probe.url.encodedPath)
            assertEquals("Bearer $TOKEN", probe.headers["Authorization"])
            assertEquals("GET", probe.method)
        }
    }

    @Test
    fun `creates a public repo on 404 and posts private false`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            server.enqueue(MockResponse.Builder().code(201).body("""{"full_name":"lwtor/cuculus-artifacts"}""").build())
            val base = server.url("/").toString().removeSuffix("/")

            val result = ArtifactRepoProvisioner.ensureRepository(
                OkHttpClient(), TOKEN, "lwtor", "cuculus-artifacts", apiBaseUrl = base,
            )

            assertTrue(result is ArtifactRepoProvisioner.Result.Created)
            assertEquals(2, server.requestCount)
            server.takeRequest() // probe
            val create = server.takeRequest()
            assertEquals("/user/repos", create.url.encodedPath)
            assertEquals("POST", create.method)
            val body = JSONObject(create.body!!.utf8())
            assertEquals("cuculus-artifacts", body.getString("name"))
            assertEquals(false, body.getBoolean("private"))
        }
    }

    @Test
    fun `uses the org endpoint when owner is an organisation`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            server.enqueue(MockResponse.Builder().code(201).body("{}").build())
            val base = server.url("/").toString().removeSuffix("/")

            ArtifactRepoProvisioner.ensureRepository(
                OkHttpClient(), TOKEN, "my-org", "apk-mirror",
                ownerIsOrganization = true, apiBaseUrl = base,
            )

            server.takeRequest()
            assertEquals("/orgs/my-org/repos", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun `surfaces an unexpected status as ReleaseApiException`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).body("""{"message":"Bad credentials"}""").build())
            val base = server.url("/").toString().removeSuffix("/")

            val failure = runCatching {
                ArtifactRepoProvisioner.ensureRepository(
                    OkHttpClient(), "bad", "lwtor", "cuculus-artifacts", apiBaseUrl = base,
                )
            }.exceptionOrNull()

            assertTrue(failure is ReleaseApiException)
            assertEquals(401, (failure as ReleaseApiException).statusCode)
            assertTrue(failure.message!!.contains("Bad credentials"))
            assertEquals(1, server.requestCount)
        }
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
