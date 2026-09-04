/*
 *
 *   Copyright 2023 Einstein Blanco
 *
 *   Licensed under the GNU General Public License v3.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.gnu.org/licenses/gpl-3.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.android.geto.service

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.android.geto.framework.notificationmanager.R as notificationR

@Singleton
class ProtectionAlertNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: AndroidNotificationManagerWrapper,
) {
    fun notifyInterrupted(label: String?) {
        notify(
            notificationId = INTERRUPTION_NOTIFICATION_ID,
            title = context.getString(R.string.protection_interrupted),
            text = label?.let {
                context.getString(R.string.protection_restarted_for, it)
            } ?: context.getString(R.string.protection_restarted),
        )
    }

    fun notifyMayBeInterrupted(label: String?) {
        notify(
            notificationId = INTERRUPTION_NOTIFICATION_ID,
            title = context.getString(R.string.protection_interrupted),
            text = label?.let {
                context.getString(R.string.open_geto_to_check_profile, it)
            } ?: context.getString(R.string.open_geto_to_check_protection),
        )
    }

    fun notifyRecoveryRequired(label: String?, detail: String? = null) {
        notify(
            notificationId = RECOVERY_NOTIFICATION_ID,
            title = context.getString(R.string.protection_needs_attention),
            text = detail ?: label?.let {
                context.getString(R.string.open_geto_to_recover_profile, it)
            } ?: context.getString(R.string.open_geto_to_recover),
        )
    }

    private fun notify(notificationId: Int, title: String, text: String) {
        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let {
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val notification = NotificationCompat.Builder(
            context,
            AndroidNotificationManagerWrapper.PROTECTION_ALERT_CHANNEL_ID,
        )
            .setSmallIcon(notificationR.drawable.baseline_settings_24)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private companion object {
        const val INTERRUPTION_NOTIFICATION_ID = 20_002
        const val RECOVERY_NOTIFICATION_ID = 20_003
    }
}
