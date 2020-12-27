package com.github.martinsucha.idedynamicsecrets

import com.goide.execution.GoRunConfigurationBase
import com.goide.execution.GoRunningState
import com.goide.execution.extension.GoRunConfigurationExtension
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import org.jdom.Element

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
        val result = buildEnvVars(configuration.project, envVarConfiguration)
        for (envVar in result.vars) {
            cmdLine.addEnvironmentVariable(envVar.key, envVar.value)
        }
        state.addProcessListener(DynamicSecretsProcessListener(result.disposable))
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
        val state = readEnvVarConfigurationFromElement(element)
        runConfiguration.putUserData(EDITOR_KEY, state)
    }

    override fun writeExternal(runConfiguration: GoRunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.getUserData(EDITOR_KEY) ?: return
        writeEnvVarConfigurationToElement(state, element)
    }

    override fun getSerializationId(): String = SERIALIZATION_ID
}

class DynamicSecretsProcessListener(private val disposable: Disposable) : ProcessListener {
    override fun startNotified(p0: ProcessEvent) {
        // no-op
    }

    override fun processTerminated(p0: ProcessEvent) {
        Disposer.dispose(disposable)
    }

    override fun onTextAvailable(p0: ProcessEvent, p1: Key<*>) {
        // no-op
    }
}
