package dev.mobimapper.mobimapper.game

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Launcher-intent visibility, never QUERY_ALL_PACKAGES. Category is a hint, not a compatibility claim. */
object GameCatalog {
    fun list(context:Context, icons:Boolean=true):List<Map<String,Any>> {
        val pm=context.packageManager
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent,PackageManager.ResolveInfoFlags.of(0))
            .filter { it.activityInfo.enabled && it.activityInfo.exported && it.activityInfo.applicationInfo.uid>=10000 && it.activityInfo.packageName !in setOf(context.packageName,"dev.mobimapper.inputlab") }
            .distinctBy { it.activityInfo.packageName }
            .map { info -> mutableMapOf<String,Any>("packageName" to info.activityInfo.packageName,
                "label" to info.loadLabel(pm).toString(),
                "gameHint" to (info.activityInfo.applicationInfo.category==ApplicationInfo.CATEGORY_GAME)).apply {
                    if(icons) runCatching {
                        val bitmap=android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888)
                        info.loadIcon(pm).apply { setBounds(0,0,192,192); draw(android.graphics.Canvas(bitmap)) }
                        val bytes=java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes); bitmap.recycle()
                        put("icon",bytes.toByteArray())
                    }
                } }
            .sortedBy { (it["label"] as String).lowercase() }
    }
    fun requireLaunchable(context:Context,packageName:String) {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        require(packageName !in setOf(context.packageName,"dev.mobimapper.inputlab") &&
            context.packageManager.queryIntentActivities(intent,PackageManager.ResolveInfoFlags.of(0)).any {
                val activity=it.activityInfo
                activity.packageName==packageName && activity.enabled && activity.exported && activity.applicationInfo.uid>=10000
            }) { "La aplicación no está visible o no tiene actividad de lanzamiento" }
    }
}
