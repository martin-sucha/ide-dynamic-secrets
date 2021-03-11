package com.github.martinsucha.idedynamicsecrets

import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.url.template.MutableParametersHolder
import com.intellij.database.dataSource.url.template.ParametersHolder
import com.intellij.database.run.ConsoleRunConfiguration
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.layout.panel
import kotlinx.coroutines.runBlocking
import java.util.LinkedList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.swing.JComponent
import javax.swing.event.DocumentListener

const val DATABASE_CREDENTIAL_PROVIDER_ID = "com.github.martinsucha.idedynamicsecrets"
const val DATABASE_PATH_PROPERTY = "com.github.martinsucha.idedynamicsecrets.path"
const val DATABASE_USERNAME_KEY_PROPERTY = "com.github.martinsucha.idedynamicsecrets.usernameKey"
const val DATABASE_PASSWORD_KEY_PROPERTY = "com.github.martinsucha.idedynamicsecrets.pwdKey"

class DynamicSecretsAuthCredentialsProvider : DatabaseAuthProvider {
    override fun intercept(
        proto: DatabaseConnectionInterceptor.ProtoConnection,
        silent: Boolean
    ): CompletionStage<DatabaseConnectionInterceptor.ProtoConnection>? {
        return CompletableFuture.supplyAsync {
            val path = proto.connectionPoint.additionalProperties[DATABASE_PATH_PROPERTY]
            if (path == null || path == "") {
                throw ConfigurationException("vault path is not specified")
            }
            val usernameKey = proto.connectionPoint.additionalProperties[DATABASE_USERNAME_KEY_PROPERTY]
            if (usernameKey == null || usernameKey == "") {
                throw ConfigurationException("vault username key is not specified")
            }
            val passwordKey = proto.connectionPoint.additionalProperties[DATABASE_PASSWORD_KEY_PROPERTY]
            if (passwordKey == null || passwordKey == "") {
                throw ConfigurationException("vault password key is not specified")
            }

            val vault = proto.runConfiguration.project.getService(Vault::class.java)
            val token = vault.getToken()

            val secret = vault.getClient().use {
                runBlocking {
                    it.fetchSecret(token, path)
                }
            }
            val lease = DatabaseLease(vault, secret.leaseID, proto.runConfiguration.project)
            Disposer.register(vault, lease)

            if (!secret.data.containsKey(usernameKey)) {
                Disposer.dispose(lease)
                throw VaultException("key $usernameKey is not present in secret")
            }

            if (!secret.data.containsKey(passwordKey)) {
                Disposer.dispose(lease)
                throw VaultException("key $passwordKey is not present in secret")
            }

            val leaseHolder = proto.runConfiguration.project.getService(DatabaseConnectionLeaseHolder::class.java)
            leaseHolder.registerNewLease(lease, proto.runConfiguration)

            proto.connectionProperties["user"] = secret.data[usernameKey]
            proto.connectionProperties["password"] = secret.data[passwordKey]

            proto
        }
    }

    override fun getId(): String = DATABASE_CREDENTIAL_PROVIDER_ID

    override fun getDisplayName(): String = "Dynamic Secrets"

    override fun isApplicable(dataSource: LocalDataSource): Boolean = true

    override fun isApplicableAsDefault(dataSource: LocalDataSource): Boolean = false

    override fun createWidget(
        project: Project?,
        credentials: DatabaseCredentials,
        dataSource: LocalDataSource
    ): DatabaseAuthProvider.AuthWidget {
        return DynamicSecretsAuthWidget()
    }

    override fun handleConnected(
        connection: DatabaseConnection,
        proto: DatabaseConnectionInterceptor.ProtoConnection
    ): CompletionStage<*>? {
        // TODO: track proto→connection so that listener can handle the correct connection.
        return null
    }
}

data class DatabaseSecretConfiguration(
    var path: String = "",
    var usernameKey: String = "username",
    var passwordKey: String = "password",
)

@Suppress("TooManyFunctions")
class DynamicSecretsAuthWidget : DatabaseAuthProvider.AuthWidget {
    private val configuration = DatabaseSecretConfiguration()
    private val panel = createPanel()

    private fun createPanel(): DialogPanel = panel {
        row("Secret path:") {
            textField(configuration::path).focused()
        }
        row("Username key:") {
            textField(configuration::usernameKey)
        }
        row("Password key:") {
            textField(configuration::passwordKey)
        }
    }

    override fun onChanged(p0: DocumentListener) {
        // no-op. Do we need to implement this?
    }

    override fun save(dataSource: LocalDataSource, copyCredentials: Boolean) {
        panel.apply()
        dataSource.additionalProperties[DATABASE_PATH_PROPERTY] = configuration.path
        dataSource.additionalProperties[DATABASE_USERNAME_KEY_PROPERTY] = configuration.usernameKey
        dataSource.additionalProperties[DATABASE_PASSWORD_KEY_PROPERTY] = configuration.passwordKey
    }

    override fun reset(dataSource: LocalDataSource, resetCredentials: Boolean) {
        configuration.path = dataSource.additionalProperties[DATABASE_PATH_PROPERTY] ?: ""
        configuration.usernameKey = dataSource.additionalProperties[DATABASE_USERNAME_KEY_PROPERTY] ?: "username"
        configuration.passwordKey = dataSource.additionalProperties[DATABASE_PASSWORD_KEY_PROPERTY] ?: "password"
        panel.reset()
    }

    override fun isPasswordChanged(): Boolean = false

    override fun hidePassword() {
        // no-op
    }

    override fun reloadCredentials() {
        // no-op
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent!!

    override fun forceSave() {
        // no-op
    }

    override fun updateFromUrl(holder: ParametersHolder) {
        // no-op
    }

    override fun updateUrl(holder: MutableParametersHolder) {
        // no-op
    }
}

class DatabaseLease(private val vault: Vault, private val leaseID: String, private val project: Project) : Disposable {
    override fun dispose() {
        val runnable = Runnable {
            try {
                val token = vault.getToken()
                vault.getClient().use {
                    runBlocking {
                        it.revokeLease(token, leaseID)
                    }
                }
            } catch (e: VaultException) {
                notifyError(project, "Error revoking lease: ${e.message}")
            }
        }
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            runnable,
            "Revoking Vault lease",
            false,
            project,
            null
        )
    }
}

class DatabaseConnectionListener : DatabaseConnectionManager.Listener {
    override fun connectionChanged(connection: DatabaseConnection, added: Boolean) {
        val leaseHolder = connection.configuration.project.getService(DatabaseConnectionLeaseHolder::class.java)
        if (added) {
            leaseHolder.registerConnection(connection)
        } else {
            val lease = leaseHolder.unregisterConnection(connection)
            if (lease != null) {
                Disposer.dispose(lease)
            }
        }
    }
}

class DatabaseConnectionLeaseHolder(@Suppress("UNUSED_PARAMETER") project: Project) {

    private val lock = Any()
    private val newLeases = mutableMapOf<Int, LinkedList<Disposable>>()
    private val leaseByConnection = mutableMapOf<Int, Disposable>()

    fun registerNewLease(d: Disposable, configuration: ConsoleRunConfiguration) {
        synchronized(lock) {
            val key = configuration.uniqueID
            val list = newLeases.computeIfAbsent(key) { LinkedList<Disposable>() }
            list.add(d)
        }
    }

    fun registerConnection(connection: DatabaseConnection) {
        synchronized(lock) {
            val list = newLeases[connection.configuration.uniqueID]
            if (list == null) {
                return
            }
            val lease = list.removeFirst()
            leaseByConnection[connectionKey(connection)] = lease
        }
    }

    fun unregisterConnection(connection: DatabaseConnection): Disposable? {
        synchronized(lock) {
            return leaseByConnection.remove(connectionKey(connection))
        }
    }

    private fun connectionKey(connection: DatabaseConnection) = System.identityHashCode(connection)
}
