package com.rider.log

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 새마을금고(MG더뱅킹) 입출금 알림을 읽어서 가계부에 넣는다. 다른 앱 알림은 보지 않고 버린다. */
class BankListener : NotificationListenerService() {

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, BankListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onListenerConnected() {
        // 앱이 꺼져 있는 동안 쌓인 알림도 한 번 훑음
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val ex = sbn.notification?.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            if (!text.contains("[입금]") && !text.contains("[출금]")) return
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                ""
            }
            if (!(title + label + sbn.packageName).contains("새마을") && !label.contains("MG")) return
            Thread { try { Money.ingest(this, text) } catch (e: Exception) {} }.start()
        } catch (e: Exception) {
        }
    }
}
