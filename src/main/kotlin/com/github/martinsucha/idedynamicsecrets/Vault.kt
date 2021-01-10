package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ScriptRunnerUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.panel
import com.intellij.util.SystemProperties
import com.intellij.util.net.ssl.CertificateManager
import com.intellij.util.xmlb.XmlSerializerUtil
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.security.cert.CertPathBuilderException

data class VaultState(
    var vaultAddress: String = "",
    var tokenHelperPath: String = "",
)

@State(
    name = "com.github.martinsucha.idedynamicsecrets.Vault",
)
class Vault(@Suppress("UNUSED_PARAMETER") project: Project) : PersistentStateComponent<VaultState>, Disposable {
    val configuration = VaultState()

    private val httpClient = HttpClient(CIO) {
        engine {
            https {
                trustManager = CertificateManager.getInstance().trustManager
            }
        }
    }

    override fun getState() = configuration
    override fun loadState(p: VaultState) {
        XmlSerializerUtil.copyBean(p, configuration)
    }

    fun getToken(): String {
        return if (configuration.tokenHelperPath != "") {
            try {
                getTokenFromHelper(configuration.tokenHelperPath, configuration.vaultAddress)
            } catch (e: ExecutionException) {
                throw VaultException("Error getting token from helper: ${e.message}", e)
            }
        } else {
            try {
                getTokenFromFile()
            } catch (e: IOException) {
                throw VaultException("Error getting token from cli file: ${e.message}\n" +
                        "Use `vault login` to create it or configure token helper.", e)
            }
        }
    }

    private suspend fun <T> wrapClientException(message: String, block: suspend () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw VaultException("$message: ${e.message}")
    } catch (e: CertPathBuilderException) {
        throw VaultException("Vault's TLS certificate check failed: ${e.message}")
    } catch (e: GeneralSecurityException) {
        throw VaultException("$message: ${e.message}")
    }

    /**
     * Fetches a vault secret by path.
     * path does not start with slash.
     */
    suspend fun fetchSecret(token: String, path: String): VaultSecret {
        val jsonData = wrapClientException("Fetch secret $path from Vault") {
            httpClient.get<String>(secretURL(configuration.vaultAddress, path)) {
                header("X-Vault-Token", token)
            }
        }
        return parseSecret(jsonData)
    }

    suspend fun revokeLease(token: String, leaseID: String) {
        // https://www.vaultproject.io/api-docs/system/leases#revoke-lease
        wrapClientException("Revoke lease $leaseID") {
            httpClient.put<String>(joinURL(configuration.vaultAddress, "/v1/sys/leases/revoke")) {
                header("X-Vault-Token", token)
                header("Content-Type", "application/json")
                body = JsonObject(mapOf("lease_id" to JsonPrimitive(leaseID))).toString()
            }
        }
    }

    override fun dispose() {
        runBlocking {
            // Close and wait for 1 seconds.
            // Should switch to httpClient.join() as in https://ktor.io/docs/client.html#releasing-resources
            // once I figure out why join is not found by the compiler.
            httpClient.close()
            delay(DISPOSE_CANCEL_DELAY_MILLIS)
            httpClient.cancel()
        }
    }
}

private const val DISPOSE_CANCEL_DELAY_MILLIS = 1000L

fun getTokenFromFile(): String {
    val homeDir = SystemProperties.getUserHome()
    return Paths.get(homeDir, ".vault-token").toFile().inputStream().use {
        it.readBytes().toString(charset("UTF-8")).trim()
    }
}

private const val TOKEN_HELPER_TIMEOUT_MILLIS = 1000L

fun getTokenFromHelper(helperPath: String, vaultAddress: String): String {
    val cmdLine = GeneralCommandLine(helperPath, "get")
    cmdLine.environment["VAULT_ADDR"] = vaultAddress
    val handler = OSProcessHandler(cmdLine)
    val token = ScriptRunnerUtil.getProcessOutput(
        handler,
        ScriptRunnerUtil.STDOUT_OUTPUT_KEY_FILTER,
        TOKEN_HELPER_TIMEOUT_MILLIS
    ).trim()
    val exitCode = handler.exitCode
    if (exitCode != 0) {
        throw ExecutionException("Token helper returned non-zero exit code $exitCode")
    }
    return token
}

fun secretURL(vaultURL: String, secretPath: String): String = joinURL(vaultURL, "/v1/$secretPath")

fun joinURL(baseURL: String, path: String): String {
    val baseURI = URI(baseURL)
    val basePathWithoutSlash = if (baseURI.path.endsWith("/")) {
        baseURI.path.dropLast(1)
    } else {
        baseURI.path
    }
    val pathWithoutSlash = if (path.startsWith("/")) {
        path.drop(1)
    } else {
        path
    }
    val joinedPath = "$basePathWithoutSlash/$pathWithoutSlash"
    return URI(
        baseURI.scheme,
        baseURI.authority,
        joinedPath,
        null,
        null,
    ).toASCIIString()
}

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class VaultSecret(
    val data: Map<String, String>,
    val leaseID: String,
)

@Suppress("ThrowsCount")
fun parseSecret(jsonData: String): VaultSecret {
    val el = Json.parseToJsonElement(jsonData)
    if (el !is JsonObject) {
        throw VaultException("Parsing vault secret: root not a JSON object")
    }
    val lease = el.getOrElse(
        "lease_id",
        {
            throw VaultException("Parsing vault secret: lease key not found")
        }
    )
    if (lease !is JsonPrimitive || !lease.isString) {
        throw VaultException("Parsing vault secret: lease is not primitive string")
    }
    val leaseID = lease.content
    val data = el.getOrElse(
        "data",
        {
            throw VaultException("Parsing vault secret: data object not found")
        }
    )
    if (data !is JsonObject) {
        throw VaultException("Parsing vault secret: data is not an object")
    }
    val secretValues = if (data.keys == setOf("data", "metadata")) {
        // This is a secret from kv engine v2, use data.data instead
        val dataData = data["data"]
        if (dataData !is JsonObject) {
            throw VaultException("Parsing vault secret: data.data is not an object")
        }
        dataData
    } else {
        data
    }
    val secretData = secretValues.mapValues {
        val value = it.value
        if (value !is JsonPrimitive || !value.isString) {
            throw VaultException("Parsing vault secret: value ${it.key} is not a string")
        }
        value.content
    }
    return VaultSecret(
        data = secretData,
        leaseID = leaseID,
    )
}

class VaultConfigurable(private val project: Project) : BoundConfigurable("Dynamic secrets") {

    private val vault = project.getService(Vault::class.java)

    override fun createPanel(): DialogPanel = panel {
        row {
            label("Vault address:")
            textField(vault.configuration::vaultAddress)
        }
        row {
            label("Token helper:")
            textFieldWithBrowseButton(
                vault.configuration::tokenHelperPath,
                browseDialogTitle = "Choose token helper program",
                project = project,
                fileChooserDescriptor = FileChooserDescriptor(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false
                )
            )
        }
    }
}
