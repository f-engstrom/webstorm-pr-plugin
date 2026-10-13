package se.personal.adoprreview.auth

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AzCliException(message: String, val notLoggedIn: Boolean = false) : Exception(message)

/**
 * Azure DevOps access tokens from `az account get-access-token`, cached in memory only.
 *
 * Each org can use its own Azure CLI login: if `~/.azure-{org}` exists, `az` runs with it as `AZURE_CONFIG_DIR`
 * (log in with `AZURE_CONFIG_DIR=~/.azure-{org} az login`). Otherwise the default `~/.azure` login is used.
 */
@Service
class AzCliToken {

    private val mutex = Mutex()
    private val cached = mutableMapOf<String, CachedToken>()

    /** For the Status tab: one entry per login dir that fetched a token this session. Never holds the token itself. */
    data class LoginStatus(val configDir: String, val account: String?, val fetchedAt: Instant, val expiresAt: Instant)

    private val logins = ConcurrentHashMap<String, LoginStatus>()

    @Volatile
    var lastError: Pair<Instant, String>? = null
        private set

    fun logins(): List<LoginStatus> = logins.values.sortedBy { it.configDir }

    /** Which login [org] uses: its own `~/.azure-{org}` directory if present, else the default login. */
    fun loginDirFor(org: String): String = configDir(org)?.path ?: "~/.azure (default)"

    /** The `az` executable the plugin would use, or null. */
    fun azPath(): String? = findAz()?.path

    /** Drops all cached tokens, so the next request asks `az` again (e.g. after `az login` as someone else). */
    suspend fun invalidateAll() {
        mutex.withLock { cached.clear() }
    }

    suspend fun authHeader(org: String): String = "Bearer ${token(org)}"

    /** Drops the cached token for [org], e.g. after a 401. */
    suspend fun invalidate(org: String) {
        mutex.withLock { cached.remove(cacheKey(configDir(org))) }
    }

    private suspend fun token(org: String): String = mutex.withLock {
        // Keyed by config dir, so creating ~/.azure-{org} takes effect without waiting for the old token to expire.
        val configDir = configDir(org)
        val key = cacheKey(configDir)
        cached[key]?.takeIf { Instant.now().isBefore(it.expiresAt.minusSeconds(60)) }?.let { return it.value }
        val token = try {
            fetch(org.lowercase(), configDir)
        } catch (e: AzCliException) {
            lastError = Instant.now() to e.message.orEmpty()
            throw e
        }
        cached[key] = token
        logins[key] = LoginStatus(configDir?.path ?: "~/.azure (default)", accountOf(token.value), Instant.now(), token.expiresAt)
        token.value
    }

    /** The signed-in account from the token's claims (decoded in memory; the token is never shown or logged). */
    private fun accountOf(token: String): String? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1].trimEnd('=')))
        val claims = json.parseToJsonElement(payload).jsonObject
        sequenceOf("upn", "unique_name", "email", "preferred_username")
            .firstNotNullOfOrNull { claims[it]?.jsonPrimitive?.contentOrNull }
    }.getOrNull()

    private fun configDir(org: String): File? =
        File(System.getProperty("user.home"), ".azure-${org.lowercase()}").takeIf { it.isDirectory }

    private fun cacheKey(configDir: File?): String = configDir?.path ?: "default"

    private suspend fun fetch(org: String, configDir: File?): CachedToken = withContext(Dispatchers.IO) {
        val az = findAz() ?: throw AzCliException("Azure CLI (az) not found. Install it with `brew install azure-cli`.")
        val command = GeneralCommandLine(az.path, "account", "get-access-token", "--resource", ADO_RESOURCE, "-o", "json")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        configDir?.let { command.withEnvironment("AZURE_CONFIG_DIR", it.path) }
        val login = if (configDir != null) "AZURE_CONFIG_DIR=~/.azure-$org az login" else "az login"
        val output = CapturingProcessHandler(command).runProcess(30_000)
        when {
            output.isTimeout -> throw AzCliException("`az account get-access-token` timed out.")
            output.exitCode != 0 -> {
                val notLoggedIn = "az login" in output.stderr
                throw AzCliException(
                    if (notLoggedIn) "Not logged in to the Azure CLI. Run `$login` in a terminal, then refresh."
                    else "`az account get-access-token` failed: ${output.stderr.trim().lineSequence().firstOrNull().orEmpty()}",
                    notLoggedIn,
                )
            }
        }
        val response = json.decodeFromString<AzTokenResponse>(output.stdout)
        val expiresAt = response.expiresOnEpoch?.let(Instant::ofEpochSecond)
            ?: response.expiresOn?.let { LocalDateTime.parse(it.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant() }
            ?: Instant.now().plusSeconds(300)
        CachedToken(response.accessToken, expiresAt)
    }

    private fun findAz(): File? =
        sequenceOf(PathEnvironmentVariableUtil.findInPath("az"), File("/opt/homebrew/bin/az"), File("/usr/local/bin/az"))
            .firstOrNull { it != null && it.canExecute() }

    private class CachedToken(val value: String, val expiresAt: Instant)

    @Serializable
    private class AzTokenResponse(
        val accessToken: String,
        @SerialName("expires_on") val expiresOnEpoch: Long? = null,
        val expiresOn: String? = null,
    )

    companion object {
        /** The Azure DevOps application ID, used as the token resource. */
        private const val ADO_RESOURCE = "499b84ac-1321-427f-aa17-267ca6975798"

        private val json = Json { ignoreUnknownKeys = true }

        fun getInstance(): AzCliToken = service()
    }
}
