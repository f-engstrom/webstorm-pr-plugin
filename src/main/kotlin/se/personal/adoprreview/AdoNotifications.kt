package se.personal.adoprreview

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import se.personal.adoprreview.api.AdoException
import se.personal.adoprreview.auth.AzCliException
import java.awt.datatransfer.StringSelection

object AdoNotifications {
    private const val GROUP = "ADO PR Review"

    /** An info balloon; with [url], it gets "Open in Browser" and "Copy Link" actions. */
    fun info(project: Project, title: String, content: String, url: String? = null) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
        if (url != null) {
            notification.addAction(NotificationAction.createSimple("Open in Browser") { BrowserUtil.browse(url) })
            notification.addAction(NotificationAction.createSimple("Copy Link") {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
            })
        }
        notification.notify(project)
    }

    fun error(project: Project, title: String, error: Throwable) {
        val content = when (error) {
            is AzCliException, is AdoException -> error.message.orEmpty()
            else -> error.message ?: error.javaClass.simpleName
        }
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }
}
