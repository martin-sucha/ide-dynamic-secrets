package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ScriptRunnerUtil
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.panel
import com.intellij.util.SystemProperties
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.IOException
import java.nio.file.Paths
import com.intellij.execution.ExecutionException
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

data class VaultState(
        var vaultAddress: String = "",
        var tokenHelperPath: String = "",
)

@State(
        name = "com.github.martinsucha.idedynamicsecrets.Vault",
)
class Vault(@Suppress("UNUSED_PARAMETER") project: Project) : PersistentStateComponent<VaultState> {
    val configuration = VaultState()

    override fun getState() = configuration
    override fun loadState(p: VaultState) {
        XmlSerializerUtil.copyBean(p, configuration)
    }

    fun getToken() : String {
        if (configuration.tokenHelperPath == "") {
            try {
                val homeDir = SystemProperties.getUserHome()
                Paths.get(homeDir, ".vault-token").toFile().inputStream().use {
                    return it.readBytes().toString(charset("UTF-8")).trim()
                }
            } catch (e: IOException) {
                return ""
            }
        }
        val cmdLine = GeneralCommandLine(configuration.tokenHelperPath, "get")
        return try {
            ScriptRunnerUtil.getProcessOutput(cmdLine, ScriptRunnerUtil.STDOUT_OUTPUT_KEY_FILTER, 1000).trim()
        } catch (e: ExecutionException) {
            ""
        }
    }

    /**
     * Fetches a vault secret by path.
     * path does not start with slash.
     */
    fun fetchSecret(token : String, path : String) : Map<String,String> {
        val jsonData = HttpRequests.request(secretURL(configuration.vaultAddress, path))
            .tuner {
                it.setRequestProperty("X-Vault-Token", token)
            }.readString()
        return parseSecret(jsonData)
    }
}

fun secretURL(vaultURL : String, secretPath : String) : String {
    val baseURI = URI(vaultURL)
    val basePathWithoutSlash = if (baseURI.path.endsWith("/")) {
        baseURI.path.dropLast(1)
    } else {
        baseURI.path
    }
    val path = "$basePathWithoutSlash/v1/$secretPath"
    return URI(
        baseURI.scheme,
        baseURI.authority,
        path,
        null,
        null,
    ).toASCIIString()
}

class VaultException(message:String): Exception(message)

fun parseSecret(jsonData : String) : Map<String, String> {
    val el = Json.parseToJsonElement(jsonData)
    if (el !is JsonObject) {
        throw VaultException("Parsing vault secret: root not a JSON object")
    }
    val data = el.getOrElse("data", {
        throw VaultException("Parsing vault secret: data object not found")
    })
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
    return secretValues.mapValues {
        val value = it.value
        if (value !is JsonPrimitive || !value.isString) {
            throw VaultException("Parsing vault secret: value ${it.key} is not a string")
        }
        value.content
    }
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
            textFieldWithBrowseButton(vault.configuration::tokenHelperPath,
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