package dev.cuculus.update

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Publish-side helper that guarantees a shared artifact repository exists before a release
 * pipeline uploads APKs into it. It is meant to be called from CI / a build script with a token —
 * **never from the app at runtime**: creating a GitHub repository is a write operation that needs
 * credentials, and those credentials must not ship inside an app binary.
 *
 * The operation is idempotent: if `GET /repos/{owner}/{repo}` succeeds the repository is already
 * present and no write happens; only a 404 triggers `POST /user/repos` with `private = false`.
 *
 * Runtime update reads need no token at all — a public artifact repository is read anonymously by
 * [Updater] through [UpdateSourceMode.ARTIFACT_REPO].
 */
object ArtifactRepoProvisioner {

    /** Outcome of [ensureRepository]. */
    sealed interface Result {
        /** The repository already existed; nothing was written. */
        data object AlreadyExists : Result

        /** The public repository was created. */
        data object Created : Result
    }

    private val JSON_MEDIA_TYPE = "application/vnd.github+json".toMediaType()

    /**
     * Ensures a public repository named [repository] exists for [owner], creating it if needed.
     *
     * @param token a GitHub token with `repo` / `public_repo` scope; supplied by CI secrets.
     * @param owner the account or organisation that will own the artifact repository. When it is
     *   the authenticated user, `POST /user/repos` is used; for an organisation, pass the org name
     *   and set [ownerIsOrganization] = true so `POST /orgs/{org}/repos` is used instead.
     * @param apiBaseUrl override for GitHub Enterprise; defaults to public GitHub.
     * @throws ReleaseApiException when GitHub answers with an unexpected status.
     * @throws IOException on transport failure.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun ensureRepository(
        client: OkHttpClient,
        token: String,
        owner: String,
        repository: String,
        ownerIsOrganization: Boolean = false,
        apiBaseUrl: String = UpdaterConfigDefaults.API_BASE_URL,
    ): Result {
        val base = apiBaseUrl.trimEnd('/')

        // Probe first: GET is cheap and read-only, and makes the whole operation idempotent.
        val probe = Request.Builder()
            .url("$base/repos/${segment(owner)}/${segment(repository)}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(probe).execute().use { response ->
            when {
                response.isSuccessful -> return Result.AlreadyExists
                response.code != 404 -> throw releaseError(response.code, response.body?.string().orEmpty())
            }
        }

        // 404: create it. User accounts and organisations use different endpoints.
        val body = JSONObject()
            .put("name", repository)
            .put("private", false)
            .put("description", "Shared APK artifact repository for the Cuculus update family")
            .put("auto_init", true)
            .toString()
        val createPath = if (ownerIsOrganization) {
            "/orgs/${segment(owner)}/repos"
        } else {
            "/user/repos"
        }
        val create = Request.Builder()
            .url("$base$createPath")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(create).execute().use { response ->
            if (!response.isSuccessful) throw releaseError(response.code, response.body?.string().orEmpty())
            return Result.Created
        }
    }

    private fun releaseError(statusCode: Int, body: String): ReleaseApiException {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf(String::isNotBlank)
        return ReleaseApiException(
            statusCode,
            message ?: "GitHub artifact repository request failed (HTTP $statusCode)",
        )
    }

    private fun segment(value: String) =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private const val GITHUB_API_VERSION = "2026-03-10"
    private const val USER_AGENT = "cuculus-update"
}
