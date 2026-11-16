package com.haitham.voiceassistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionItem(val permission: String, val title: String, val why: String, val required: Boolean)

object Permissions {
    val items: List<PermissionItem> = buildList {
        add(PermissionItem(Manifest.permission.RECORD_AUDIO, "الميكروفون", "لسماع كلمة التنبيه والأوامر (المعالجة داخل الهاتف فقط)", true))
        add(PermissionItem(Manifest.permission.READ_CONTACTS, "جهات الاتصال", "للبحث عن الاسم الذي تنطقه", true))
        add(PermissionItem(Manifest.permission.CALL_PHONE, "إجراء المكالمات", "للاتصال مباشرة دون فتح شاشة الاتصال", true))
        if (Build.VERSION.SDK_INT >= 33) add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS, "الإشعارات", "لعرض إشعار «المساعد نشط» وتنبيه التفعيل بعد إعادة التشغيل", true))
        add(PermissionItem(Manifest.permission.READ_CALL_LOG, "سجل المكالمات (اختياري)", "لأمر «أعد الاتصال بآخر رقم» من سجل الهاتف", false))
    }

    val requiredPermissions: Array<String> get() = items.filter { it.required }.map { it.permission }.toTypedArray()

    fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun missingRequired(context: Context) = items.filter { it.required && !granted(context, it.permission) }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // the user explicitly asks for it from the settings screen
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try { context.startActivity(i) } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    fun requestOverlay(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }

    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}
