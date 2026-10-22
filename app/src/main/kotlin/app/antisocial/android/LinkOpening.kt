/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.antisocial.core.Platform

/** Enabling our own intent components does not approve their domains in Android.
 * User domain approval remains exclusively in the system's supported-links screen.
 */
object LinkOpening {
    fun component(context: Context,platform: Platform)=ComponentName(context.packageName,"${context.packageName}.links.${platform.name}")
    @Synchronized fun sync(context: Context,selected: kotlin.collections.Collection<Platform>) {
        val manager=context.packageManager
        // Video links are media entry points, independent of social tab choices.
        // Android still gives the user control over supported-link approval.
        val chosen=selected.toSet()+Platform.YOUTUBE
        val changes=Platform.entries.filterNot {it==Platform.RSS}.mapNotNull {platform->
            val name=component(context,platform)
            val enabled=if(platform in chosen)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if(manager.getComponentEnabledSetting(name)==enabled)null else Triple(name,enabled,PackageManager.DONT_KILL_APP)
        }
        if(changes.isEmpty())return
        if(Build.VERSION.SDK_INT>=33)manager.setComponentEnabledSettings(changes.map {(name,enabled,flags)->PackageManager.ComponentEnabledSetting(name,enabled,flags)})
        else changes.forEach {(name,enabled,flags)->manager.setComponentEnabledSetting(name,enabled,flags)}
    }
    fun settingsIntent(context: Context): Intent {
        val address=Uri.parse("package:${context.packageName}")
        val direct=if(Build.VERSION.SDK_INT>=31)Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,address)else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,address)
        return if(direct.resolveActivity(context.packageManager)!=null)direct else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,address)
    }
}
