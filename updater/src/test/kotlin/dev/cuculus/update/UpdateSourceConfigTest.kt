package dev.cuculus.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the source-resolution rules that drive the dual-source update flow. */
class UpdateSourceConfigTest {

    private fun config(
        owner: String = "lwtor",
        repository: String = "video-archive",
        block: (UpdaterConfig.() -> UpdaterConfig) = { this },
    ): UpdaterConfig = UpdaterConfig(
        owner = owner,
        repository = repository,
        fileProviderAuthority = "dev.cuculus.update.sample.updater",
    ).block()

    @Test
    fun `default mode reads the current repository and keeps the api base`() {
        val c = config()
        assertEquals(UpdateSourceMode.CURRENT_REPO, c.sourceMode)
        assertEquals(ArtifactRepoRef("lwtor", "video-archive"), c.effectiveRepo)
        assertEquals(UpdaterConfigDefaults.API_BASE_URL, c.apiBaseUrl)
    }

    @Test
    fun `artifact mode falls back to the built-in public repository`() {
        val c = config { copy(sourceMode = UpdateSourceMode.ARTIFACT_REPO) }
        assertEquals(
            ArtifactRepoRef(ArtifactRepoDefaults.OWNER, ArtifactRepoDefaults.REPOSITORY),
            c.effectiveRepo,
        )
    }

    @Test
    fun `artifact mode honours a caller-supplied repository`() {
        val c = config {
            copy(
                sourceMode = UpdateSourceMode.ARTIFACT_REPO,
                artifactRepo = ArtifactRepoRef("my-org", "apk-mirror"),
            )
        }
        assertEquals(ArtifactRepoRef("my-org", "apk-mirror"), c.effectiveRepo)
    }

    @Test
    fun `namespace defaults to the app repository and honours an override`() {
        val defaultNs = config { copy(sourceMode = UpdateSourceMode.ARTIFACT_REPO) }
        assertEquals("video-archive", defaultNs.effectiveNamespace)

        val customNs = config {
            copy(sourceMode = UpdateSourceMode.ARTIFACT_REPO, artifactNamespace = "vault")
        }
        assertEquals("vault", customNs.effectiveNamespace)

        val blankNs = config {
            copy(sourceMode = UpdateSourceMode.ARTIFACT_REPO, artifactNamespace = "   ")
        }
        assertEquals("video-archive", blankNs.effectiveNamespace)
    }

    @Test
    fun `effective namespace is irrelevant but harmless in current repo mode`() {
        val c = config { copy(artifactNamespace = "ignored") }
        assertEquals(ArtifactRepoRef("lwtor", "video-archive"), c.effectiveRepo)
    }
}
