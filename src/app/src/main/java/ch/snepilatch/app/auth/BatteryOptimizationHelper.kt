package ch.snepilatch.app.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import ch.snepilatch.app.util.LokiLogger

/**
 * Helpers to surface the OS-level switches the user needs to flip for
 * [TokenRefreshWorker] to actually fire reliably:
 *
 *  - Standard Doze whitelist (works on every device).
 *  - OEM-specific autostart / background-app screens (Xiaomi, Huawei, Oppo,
 *    Vivo, OnePlus, Samsung, etc.). These are non-standard and not all OEMs
 *    expose them; we probe with `resolveActivity` and only offer the entry
 *    if one is installed.
 *
 * Intent list curated from github.com/judemanutd/AutoStarter and
 * dontkillmyapp.com. The corresponding package names are declared in
 * `<queries>` in AndroidManifest.xml so `resolveActivity` works under
 * Android 11+ package visibility.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOpt"

    private val OEM_AUTOSTART_INTENTS: List<Pair<String, String>> = listOf(
        // Xiaomi (MIUI)
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo (ColorOS) / Realme
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo (FuntouchOS / OriginOS)
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // OnePlus (older OxygenOS — newer ones reuse ColorOS entries above)
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Letv (LeEco)
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        // Asus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        // Samsung — no first-class autostart screen, battery UI is the closest exported activity
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Nokia (EvenWell)
        "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Universal Doze whitelist prompt. No-op if already exempt. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Could not open battery-optimization prompt: ${e.message?.take(120)}")
        }
    }

    /** True if the device exposes a known OEM autostart / background-restrict screen. */
    fun hasOemAutostartScreen(context: Context): Boolean = findOemAutostartIntent(context) != null

    /** Opens the OEM autostart screen if available. Returns false if none resolved. */
    fun openOemAutostartSettings(context: Context): Boolean {
        val intent = findOemAutostartIntent(context) ?: return false
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Could not open OEM autostart screen: ${e.message?.take(120)}")
            false
        }
    }

    private fun findOemAutostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        for ((pkg, cls) in OEM_AUTOSTART_INTENTS) {
            val intent = Intent().setClassName(pkg, cls)
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }
}
