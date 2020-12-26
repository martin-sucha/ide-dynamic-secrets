package com.github.martinsucha.idedynamicsecrets

import com.goide.execution.GoRunConfigurationBase
import com.goide.execution.GoRunningState
import com.goide.execution.extension.GoRunConfigurationExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.ClearableLazyValue
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.layout.panel
import org.jdom.Element
import javax.swing.JComponent

class DynamicSecretsGoRunConfigurationExtension : GoRunConfigurationExtension() {
    override fun patchCommandLine(
        configuration: GoRunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: TargetedCommandLineBuilder,
        runnerId: String,
        state: GoRunningState<out GoRunConfigurationBase<*>>,
        commandLineType: GoRunningState.CommandLineType,
    ) {
        if (commandLineType != GoRunningState.CommandLineType.RUN) {
            return
        }
        val envVarConfiguration = configuration.getUserData(EDITOR_KEY) ?: return
        val vault = configuration.project.getService(Vault::class.java)
        val token = vault.getToken()
        for (secretConfiguration in envVarConfiguration.secrets) {
            val secret = vault.fetchSecret(token, secretConfiguration.path)
            for (mapping in secretConfiguration.envVarMapping) {
                val value = secret[mapping.secretValueName]
                if (value == null) {
                    val keys = secret.keys.sorted()
                    throw VaultException(
                        "Secret ${secretConfiguration.path} does not have key " +
                            "${mapping.secretValueName}\nThe following keys are available: $keys"
                    )
                }
                cmdLine.addEnvironmentVariable(mapping.envVarName, value)
            }
        }
        state.addProcessListener(DynamicSecretsProcessListener())
    }

    override fun isApplicableFor(p0: GoRunConfigurationBase<*>): Boolean = true
    override fun isEnabledFor(p0: GoRunConfigurationBase<*>, p1: RunnerSettings?): Boolean = true

    override fun getEditorTitle(): String {
        return "Dynamic Secrets"
    }

    override fun <P : GoRunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P> {
        return DynamicSecretsSettingsEditor(configuration.project)
    }

    override fun readExternal(runConfiguration: GoRunConfigurationBase<*>, element: Element) {
        val secretsElement = element.getChild(ELEMENT_SECRETS)
        val secrets = mutableListOf<EnvVarSecret>()
        if (secretsElement != null) {
            for (secretElement in secretsElement.getChildren(ELEMENT_SECRETS_ITEM)) {
                secrets.add(parseSecretElement(secretElement))
            }
        }
        val state = EnvVarConfiguration(secrets)

        runConfiguration.putUserData(EDITOR_KEY, state)
    }

    override fun writeExternal(runConfiguration: GoRunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.getUserData(EDITOR_KEY) ?: return
        if (state.secrets.isNotEmpty()) {
            val secretsElement = Element(ELEMENT_SECRETS)
            for (secret in state.secrets) {
                val secretElement = Element(ELEMENT_SECRETS_ITEM)
                secretElement.setAttribute(ATTR_PATH, secret.path)
                for (envVar in secret.envVarMapping) {
                    val envVarElement = Element(ELEMENT_SECRETS_ENV_VAR)
                    envVarElement.setAttribute(ATTR_ENV_VAR_NAME, envVar.envVarName)
                    envVarElement.setAttribute(ATTR_ENV_VAR_SECRET_VALUE_NAME, envVar.secretValueName)
                    secretElement.addContent(envVarElement)
                }
                secretsElement.addContent(secretElement)
            }
            element.addContent(secretsElement)
        }
    }

    override fun getSerializationId(): String = SERIALIZATION_ID
}

class ConfigurationException(message: String) : Exception(message)

@Suppress("ThrowsCount")
fun parseSecretElement(secretElement: Element): EnvVarSecret {
    val path = secretElement.getAttributeValue(ATTR_PATH)
    if (path == null) {
        throw ConfigurationException("secret.path is not present")
    }
    val mappings = mutableListOf<EnvVarSecretMapping>()
    for (envVarElement in secretElement.getChildren(ELEMENT_SECRETS_ENV_VAR)) {
        val envVarName = envVarElement.getAttributeValue(ATTR_ENV_VAR_NAME)
        if (envVarName == null) {
            throw ConfigurationException("secret.envVar.name is not present")
        }
        val secretValueName = envVarElement.getAttributeValue(ATTR_ENV_VAR_SECRET_VALUE_NAME)
        if (secretValueName == null) {
            throw ConfigurationException("secret.envVar.secretValueName is not present")
        }
        mappings.add(EnvVarSecretMapping(envVarName, secretValueName))
    }
    return EnvVarSecret(
        path = path,
        envVarMapping = mappings,
    )
}

private const val SERIALIZATION_ID = "com.github.martinsucha.idedynamicsecrets"
private const val ELEMENT_SECRETS = "secrets"
private const val ELEMENT_SECRETS_ITEM = "secret"
private const val ELEMENT_SECRETS_ENV_VAR = "envVar"
private const val ATTR_PATH = "path"
private const val ATTR_ENV_VAR_NAME = "name"
private const val ATTR_ENV_VAR_SECRET_VALUE_NAME = "secretValueName"

class DynamicSecretsProcessListener : ProcessListener {
    override fun startNotified(p0: ProcessEvent) {
        println("start notified")
    }

    override fun processTerminated(p0: ProcessEvent) {
        println("process terminated")
    }

    override fun onTextAvailable(p0: ProcessEvent, p1: Key<*>) {
        // no-op
    }
}

private val EDITOR_KEY = Key<EnvVarConfiguration>("Dynamic Secrets settings")

class DynamicSecretsSettingsEditor<P : RunConfigurationBase<*>>(val project: Project) : SettingsEditor<P>() {

    private var disposable: Disposable? = null

    private val panel = object : ClearableLazyValue<DialogPanel>() {
        override fun compute(): DialogPanel {
            if (disposable == null) {
                disposable = Disposer.newDisposable()
            }
            val panel = createPanel()
            panel.registerValidators(disposable!!)
            return panel
        }
    }

    private val envVarEditor = object : ClearableLazyValue<EnvVarEditor>() {
        override fun compute(): EnvVarEditor {
            return EnvVarEditor(project)
        }
    }

    override fun resetEditorFrom(configuration: P) {
        val state = configuration.getUserData(EDITOR_KEY)
        if (state != null) {
            envVarEditor.value.state = state
        }
    }

    override fun applyEditorTo(configuration: P) {
        configuration.putUserData(EDITOR_KEY, envVarEditor.value.state)
    }

    fun createPanel(): DialogPanel = panel {
        row {
            label("Secrets to expose as environment variables:")
        }
        row {
            scrollPane(envVarEditor.value.component)
        }
    }

    override fun createEditor(): JComponent {
        return panel.value
    }

    override fun disposeEditor() {
        disposable?.let {
            Disposer.dispose(it)
            disposable = null
        }

        panel.drop()
    }
}
