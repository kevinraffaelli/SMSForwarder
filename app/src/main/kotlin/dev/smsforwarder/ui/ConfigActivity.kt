package dev.smsforwarder.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import kotlinx.coroutines.launch

/**
 * The single Config Activity (Specification §2, §4). Compose UI for:
 *  - connecting the Telegram bot (token, chat detection, test message);
 *  - the unattended-setup checklist (SMS permission, battery, app
 *    hibernation, restricted settings) — Specification §9;
 *  - editing forwarding rules and the forwarding on/off switch.
 *
 * Closing the Activity has no effect on SMS handling: the manifest-declared
 * SMS receiver runs whether or not the app is open.
 */
class ConfigActivity : ComponentActivity() {

    private val app get() = (application as SmsForwarderApp)

    private var checklist by mutableStateOf(SetupChecklist())

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshChecklist() }

    private val contactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* senderName rules simply evaluate false without it (§5.3). */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Asking once also surfaces Android 15's restricted-settings block,
        // which is what makes "Allow restricted settings" appear in App info.
        if (!hasSmsPermission()) smsPermLauncher.launch(Manifest.permission.RECEIVE_SMS)

        val container = app.container
        setContent {
            SmsForwarderTheme {
                ConfigScreen(
                    viewModel = rememberConfigViewModel(container),
                    checklist = checklist,
                    onRequestSms = { smsPermLauncher.launch(Manifest.permission.RECEIVE_SMS) },
                    onRequestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    onRequestBatteryOpt = ::requestIgnoreBatteryOptimizations,
                    onManageUnusedAppRestrictions = ::openUnusedAppRestrictions,
                    onOpenAppInfo = ::openAppInfo,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshChecklist() {
        checklist = checklist.copy(
            smsPermission = hasSmsPermission(),
            batteryUnrestricted = getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) == true,
            showRestrictedSettingsHint = Build.VERSION.SDK_INT >= 35, // Android 15 (VANILLA_ICE_CREAM)
        )
        lifecycleScope.launch {
            val status = runCatching {
                PackageManagerCompat.getUnusedAppRestrictionsStatus(this@ConfigActivity).await()
            }.getOrNull()
            checklist = checklist.copy(
                unusedAppRestrictionsOff = when (status) {
                    UnusedAppRestrictionsConstants.DISABLED -> true
                    UnusedAppRestrictionsConstants.API_30_BACKPORT,
                    UnusedAppRestrictionsConstants.API_30,
                    UnusedAppRestrictionsConstants.API_31 -> false
                    else -> null // ERROR / FEATURE_NOT_AVAILABLE: nothing to switch off.
                }
            )
        }
    }

    // The unattended forwarder needs network access during Doze (Specification §9);
    // the app is sideloaded, so the Play policy behind this lint doesn't apply.
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun openUnusedAppRestrictions() {
        runCatching {
            startActivity(IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName))
        }.onFailure { openAppInfo() }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
