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

data class VaultState(
        var vaultAddress: String = "",
        var tokenHelperPath: String = "",
)

@State(
        name = "com.github.martinsucha.idedynamicsecrets.Vault",
)
class Vault(project: Project) : PersistentStateComponent<VaultState> {
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