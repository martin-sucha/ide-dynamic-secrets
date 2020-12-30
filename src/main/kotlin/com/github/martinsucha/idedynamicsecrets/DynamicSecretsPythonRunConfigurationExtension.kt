package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SettingsEditor
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import org.jdom.Element
import java.util.LinkedList

class DynamicSecretsPythonRunConfigurationExtension : PythonRunConfigurationExtension() {
    /**
     * disposeOnProcessEnd holds the disposable that we need to dispose once the process finishes.
     * We just store them in queue since there is no way to add the process listener in patchCommandLine (like for Go)
     * and we need to somehow get the reference to revoke the leases. This creates a race condition, but I guess most
     * of the time we will start only a single process at a time anyway.
     */
    private val disposeOnProcessEnd = LinkedList<Disposable>()

    override fun patchCommandLine(
        configuration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String
    ) {
        val envVarConfiguration = configuration.getUserData(EDITOR_KEY) ?: return
        val result = buildEnvVars(configuration.project, envVarConfiguration)
        cmdLine.environment.putAll(result.vars)
        synchronized(disposeOnProcessEnd) {
            disposeOnProcessEnd.addLast(result.disposable)
        }
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

    override fun attachToProcess(
        configuration: AbstractPythonRunConfiguration<*>,
        handler: ProcessHandler,
        runnerSettings: RunnerSettings?
    ) {
        super.attachToProcess(configuration, handler, runnerSettings)
        val disposable = synchronized(disposeOnProcessEnd) {
            disposeOnProcessEnd.removeFirst()
        }
        handler.addProcessListener(DynamicSecretsProcessListener(disposable))
    }
}
