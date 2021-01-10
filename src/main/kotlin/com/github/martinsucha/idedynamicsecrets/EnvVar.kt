package com.github.martinsucha.idedynamicsecrets

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.ClearableLazyValue
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.layout.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.xmlb.XmlSerializerUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.jdom.Element
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.lang.IndexOutOfBoundsException
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.table.AbstractTableModel

class EnvVarEditor(private val project: Project) {

    private val collectionListModel = CollectionListModel<EnvVarSecret>()
    private val theList = JBList(collectionListModel)

    val component: JComponent

    init {
        theList.cellRenderer = EnvVarListCellRenderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(p0: MouseEvent): Boolean {
                doEdit()
                return true
            }
        }.installOn(theList)

        val decorator = ToolbarDecorator.createDecorator(theList)
        decorator.disableUpDownActions()
        decorator.setEditAction {
            doEdit()
        }
        decorator.setAddAction {
            val newSecret = EnvVarSecret()
            val editDialog = EditEnvVarSecretDialog(newSecret)
            if (editDialog.showAndGet()) {
                collectionListModel.add(newSecret)
            }
        }
        component = decorator.createPanel()
    }

    private fun doEdit() {
        val selected = theList.selectedIndex
        if (selected < 0) {
            return
        }
        val item = collectionListModel.getElementAt(selected)
        val editedItem = item.deepCopy()
        val editDialog = EditEnvVarSecretDialog(editedItem)
        if (editDialog.showAndGet()) {
            editedItem.cleanup()
            XmlSerializerUtil.copyBean(editedItem, item)
        }
    }

    var state: EnvVarConfiguration
        get() = EnvVarConfiguration(collectionListModel.toList())
        set(value) {
            collectionListModel.removeAll()
            collectionListModel.add(value.secrets)
        }
}

class EditEnvVarSecretDialog(secret: EnvVarSecret) : DialogWrapper(true) {

    private val myPanel = itemPanel(secret)

    init {
        title = "Edit Secret"
        super.init()
    }

    override fun createCenterPanel(): JComponent? = myPanel

    private fun itemPanel(secret: EnvVarSecret) = panel {
        row("Secret path:") {
            textField(secret::path)
        }
        row {
            scrollPane(varsTable(secret))
        }
    }

    private fun varsTable(secret: EnvVarSecret): JComponent {
        val tableModel = EnvVarTableModel(secret)
        val table = JBTable(tableModel)
        @Suppress("MagicNumber")
        table.minimumSize = Dimension(200, 100)
        val decorator = ToolbarDecorator.createDecorator(table)
        decorator.setAddAction {
            secret.envVarMapping.add(EnvVarSecretMapping())
            tableModel.fireTableDataChanged()
        }
        decorator.setRemoveAction {
            secret.envVarMapping.removeAt(table.selectedRow)
            tableModel.fireTableDataChanged()
        }
        return decorator.createPanel()
    }
}

data class EnvVarConfiguration(val secrets: List<EnvVarSecret>)

data class EnvVarSecret(
    var path: String = "",
    var envVarMapping: MutableList<EnvVarSecretMapping> = mutableListOf(),
) {
    fun deepCopy(): EnvVarSecret {
        val mappingCopies = mutableListOf<EnvVarSecretMapping>()
        mappingCopies.addAll(envVarMapping.map { it.copy() })
        return copy(
            envVarMapping = mappingCopies
        )
    }

    fun cleanup() {
        envVarMapping.removeIf { it.envVarName == "" || it.secretValueName == "" }
    }
}

data class EnvVarSecretMapping(
    var envVarName: String = "",
    var secretValueName: String = "",
)

class EnvVarListCellRenderer : ColoredListCellRenderer<EnvVarSecret>() {
    override fun customizeCellRenderer(
        list: JList<out EnvVarSecret>,
        value: EnvVarSecret,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value.path == "") {
            append("path not set", SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
        } else {
            append(value.path)
        }
        append(" ")
        append(value.envVarMapping.joinToString { it.envVarName }, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

class EnvVarTableModel(private val secret: EnvVarSecret) : AbstractTableModel() {
    override fun getRowCount(): Int = secret.envVarMapping.size

    override fun getColumnCount(): Int = 2

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val mapping = secret.envVarMapping[rowIndex]
        return when (columnIndex) {
            0 -> mapping.envVarName
            1 -> mapping.secretValueName
            else -> throw IndexOutOfBoundsException("Invalid column index $columnIndex")
        }
    }

    override fun getColumnName(column: Int): String {
        return when (column) {
            0 -> "Env var name"
            1 -> "Secret value key"
            else -> throw IndexOutOfBoundsException("Invalid column index $column")
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val mapping = secret.envVarMapping[rowIndex]
        when (columnIndex) {
            0 -> mapping.envVarName = aValue as String
            1 -> mapping.secretValueName = aValue as String
            else -> throw IndexOutOfBoundsException("Invalid column index $columnIndex")
        }
    }
}

data class EnvVarsResult(
    val vars: Map<String, String>,
    val disposable: Disposable,
)

fun buildEnvVarsWithProgress(project: Project, envVarConfiguration: EnvVarConfiguration): EnvVarsResult {
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(
        ThrowableComputable<EnvVarsResult, ExecutionException> {
            buildEnvVars(project, envVarConfiguration, ProgressIndicatorProvider.getGlobalProgressIndicator()!!)
        },
        "Fetching vault secrets",
        true,
        project,
    )
}

fun buildEnvVars(
    project: Project,
    envVarConfiguration: EnvVarConfiguration,
    indicator: ProgressIndicator
): EnvVarsResult {
    val vault = project.getService(Vault::class.java)
    val token = try {
        vault.getToken()
    } catch (e: VaultException) {
        throw ExecutionException(e)
    }
    val leaseIDs = mutableSetOf<String>()
    val leaseDisposable = RunConfigurationLeases(vault, leaseIDs, project)
    Disposer.register(vault, leaseDisposable)
    val envVars = try {
        fetchEnvVars(vault, token, envVarConfiguration, leaseIDs, indicator)
    } catch (e: VaultException) {
        Disposer.dispose(leaseDisposable)
        throw ExecutionException(e)
    }
    return EnvVarsResult(
        vars = envVars,
        disposable = leaseDisposable,
    )
}

private const val CANCEL_CHECKER_DELAY_MILLIS = 10L

fun fetchEnvVars(
    vault: Vault,
    token: String,
    envVarConfiguration: EnvVarConfiguration,
    leaseIDs: MutableSet<String>,
    indicator: ProgressIndicator,
): Map<String, String> {
    if (envVarConfiguration.secrets.isEmpty()) {
        return mapOf()
    }
    return runBlocking {
        val envVars = mutableMapOf<String, String>()
        val cancelChecker = async {
            while (isActive) {
                indicator.checkCanceled()
                delay(CANCEL_CHECKER_DELAY_MILLIS)
            }
        }
        // We fetch first secret synchronously so that when the Vault's server certificate is not trusted and
        // the user rejects the certificate, we don't show the dialog multiple times.
        val firstSecret = envVarConfiguration.secrets.first()
        fetchEnvVarsForSingleSecret(vault, token, firstSecret, leaseIDs, envVars)
        val jobs = mutableListOf<Job>()
        for (secretConfiguration in envVarConfiguration.secrets.subList(1, envVarConfiguration.secrets.size)) {
            jobs.add(
                async {
                    fetchEnvVarsForSingleSecret(vault, token, secretConfiguration, leaseIDs, envVars)
                }
            )
        }
        jobs.joinAll()
        cancelChecker.cancelAndJoin()
        envVars
    }
}

private suspend fun fetchEnvVarsForSingleSecret(
    vault: Vault,
    token: String,
    secretConfiguration: EnvVarSecret,
    leaseIDs: MutableSet<String>,
    envVars: MutableMap<String, String>
) {
    val secret = vault.fetchSecret(token, secretConfiguration.path)
    if (secret.leaseID != "") {
        leaseIDs.add(secret.leaseID)
    }
    for (mapping in secretConfiguration.envVarMapping) {
        val value = secret.data[mapping.secretValueName]
        if (value == null) {
            val keys = secret.data.keys.sorted()
            throw VaultException(
                "Secret ${secretConfiguration.path} does not have key " +
                    "${mapping.secretValueName}\nThe following keys are available: $keys"
            )
        }
        envVars[mapping.envVarName] = value
    }
}

class RunConfigurationLeases(
    private val vault: Vault,
    private val leaseIDs: Set<String>,
    private val project: Project,
) : Disposable {
    override fun dispose() {
        val token = try {
            vault.getToken()
        } catch (e: VaultException) {
            notifyError(project, "Error revoking leases: ${e.message}")
            return
        }
        runBlocking {
            // supervisorScope so that we don't cancel revoking all other leases if one revoke fails.
            supervisorScope {
                val jobs = mutableListOf<Job>()
                for (leaseID in leaseIDs) {
                    jobs.add(
                        launch {
                            try {
                                vault.revokeLease(token, leaseID)
                            } catch (e: VaultException) {
                                notifyError(project, "Error revoking lease: ${e.message}")
                            }
                        }
                    )
                }
                jobs.joinAll()
            }
        }
    }
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

internal const val SERIALIZATION_ID = "com.github.martinsucha.idedynamicsecrets"
private const val ELEMENT_SECRETS = "secrets"
private const val ELEMENT_SECRETS_ITEM = "secret"
private const val ELEMENT_SECRETS_ENV_VAR = "envVar"
private const val ATTR_PATH = "path"
private const val ATTR_ENV_VAR_NAME = "name"
private const val ATTR_ENV_VAR_SECRET_VALUE_NAME = "secretValueName"

internal val EDITOR_KEY = Key<EnvVarConfiguration>("Dynamic Secrets settings")

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

fun readEnvVarConfigurationFromElement(element: Element): EnvVarConfiguration {
    val secretsElement = element.getChild(ELEMENT_SECRETS)
    val secrets = mutableListOf<EnvVarSecret>()
    if (secretsElement != null) {
        for (secretElement in secretsElement.getChildren(ELEMENT_SECRETS_ITEM)) {
            secrets.add(parseSecretElement(secretElement))
        }
    }
    return EnvVarConfiguration(secrets)
}

fun writeEnvVarConfigurationToElement(state: EnvVarConfiguration, element: Element) {
    if (state.secrets.isEmpty()) {
        return
    }

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
