package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import org.jdom.Element

class DynamicSecretsPythonRunConfigurationExtension : PythonRunConfigurationExtension() {
    override fun patchCommandLine(
        configuration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String
    ) {
        val envVarConfiguration = configuration.getUserData(EDITOR_KEY) ?: return
        val result = buildEnvVars(configuration.project, envVarConfiguration)
        cmdLine.environment.putAll(result.vars)
    }

    override fun getSerializationId(): String {
        return SERIALIZATION_ID
    }

    override fun readExternal(runConfiguration: AbstractPythonRunConfiguration<*>, element: Element) {
        val state = readEnvVarConfigurationFromElement(element)
        runConfiguration.putUserData(EDITOR_KEY, state)
    }

    override fun writeExternal(runConfiguration: AbstractPythonRunConfiguration<*>, element: Element) {
        val state = runConfiguration.getUserData(EDITOR_KEY) ?: return
        writeEnvVarConfigurationToElement(state, element)
    }

    override fun <P : AbstractPythonRunConfiguration<*>> createEditor(configuration: P): SettingsEditor<P> {
        return DynamicSecretsSettingsEditor(configuration.project)
    }

    override fun getEditorTitle(): String {
        return "Dynamic Secrets"
    }

    override fun isApplicableFor(p0: AbstractPythonRunConfiguration<*>): Boolean = true
    override fun isEnabledFor(p0: AbstractPythonRunConfiguration<*>, p1: RunnerSettings?): Boolean = true
}
