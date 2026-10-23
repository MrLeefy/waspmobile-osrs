package com.example.waspmobile.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.example.waspmobile.engine.WaspMobileState
import java.io.File
import java.io.FileInputStream

class StoreInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                WaspMobileState.log("Store-Session install complete! Accessibility Restricted Settings unlocked.")
                Toast.makeText(
                    context,
                    "Unlocked! Now tap 'Enable Overlay' -> Installed apps -> WaspMobile OSRS Overlay!",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status $status"
                WaspMobileState.log("Store-Session notice: $msg")
            }
        }
    }

    companion object {
        /**
         * Re-installs WaspMobile's own base APK via PackageInstaller.Session with
         * PACKAGE_SOURCE_STORE so Android 13/14/15/16 exempts it from Restricted Settings
         * ("Unavailable" lock in Accessibility -> Installed apps).
         */
        fun triggerStoreSessionUnlock(context: Context) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    context,
                    "Toggle 'Allow from this source' ON, then tap Unlock again!",
                    Toast.LENGTH_LONG
                ).show()
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return
            }

            try {
                val apkFile = File(context.applicationInfo.sourceDir)
                val packageInstaller = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                    }
                }

                val sessionId = packageInstaller.createSession(params)
                packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                        FileInputStream(apkFile).use { input ->
                            input.copyTo(out)
                        }
                        session.fsync(out)
                    }

                    val callbackIntent = Intent(context, StoreInstallResultReceiver::class.java)
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        callbackIntent,
                        flags
                    )
                    session.commit(pendingIntent.intentSender)
                }
                WaspMobileState.log("Triggered Store-Session self-update to unlock Accessibility.")
            } catch (e: Exception) {
                WaspMobileState.log("Unlock error: ${e.message}")
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
