package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Migration to add default extension repositories for new users.
 * Uses upsert so it's safe if repos already exist (e.g., from backup restore).
 */
class DefaultExtensionReposMigration : Migration {
    // Use ALWAYS so this runs for new installs too (not just upgrades)
    override val version: Float = Migration.ALWAYS

    private data class DefaultRepo(
        val baseUrl: String,
        val name: String,
        val shortName: String?,
        val website: String,
        val fingerprint: String,
    )

    // Default repositories to add for new users
    private val defaultRepos = listOf(
        DefaultRepo(
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            name = "Keiyoushi",
            shortName = "KEY",
            website = "https://keiyoushi.github.io/extensions/",
            fingerprint = "NOFINGERPRINT-keiyoushi",
        ),
        DefaultRepo(
            baseUrl = "https://raw.githubusercontent.com/yuzono/manga-repo/repo",
            name = "Yūzōnō",
            shortName = "YUZ",
            website = "https://github.com/yuzono/manga-repo",
            fingerprint = "NOFINGERPRINT-yuzono",
        ),
    )

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val extensionRepoRepository =
            migrationContext.get<ExtensionRepoRepository>() ?: return@withIOContext false

        for (repo in defaultRepos) {
            try {
                // Check if repo already exists (from backup or previous install)
                val existing = extensionRepoRepository.getRepo(repo.baseUrl)
                if (existing != null) {
                    logcat(LogPriority.DEBUG) { "Repo already exists, skipping: ${repo.name}" }
                    continue
                }

                // Add the default repo
                extensionRepoRepository.insertRepo(
                    repo.baseUrl,
                    repo.name,
                    repo.shortName,
                    repo.website,
                    repo.fingerprint,
                )
                logcat(LogPriority.INFO) { "Added default repo: ${repo.name}" }
            } catch (e: SaveExtensionRepoException) {
                // Already exists or other conflict, that's fine
                logcat(LogPriority.DEBUG, e) { "Repo may already exist: ${repo.name}" }
            }
        }

        return@withIOContext true
    }
}
