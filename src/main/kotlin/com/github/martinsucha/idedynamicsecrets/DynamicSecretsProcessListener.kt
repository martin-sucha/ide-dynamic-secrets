package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

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
