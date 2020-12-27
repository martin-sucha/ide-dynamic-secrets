package com.github.martinsucha.idedynamicsecrets

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

fun notifyError(project: Project, content: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("Dynamic Secrets")
        .createNotification(content, NotificationType.ERROR)
        .notify(project)
}
