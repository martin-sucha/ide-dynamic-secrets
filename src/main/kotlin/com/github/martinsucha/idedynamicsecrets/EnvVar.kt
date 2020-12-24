package com.github.martinsucha.idedynamicsecrets

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.layout.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.xmlb.XmlSerializerUtil
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
