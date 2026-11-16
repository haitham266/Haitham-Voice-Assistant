package com.haitham.voiceassistant.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Places phone calls without user confirmation (the matching logic upstream
 * guarantees we only get here when the target is clear).
 *
 * [TelecomManager.placeCall] is used first because it works from a background
 * foreground-service context without needing to start an Activity (which
 * Android 10+ restricts). ACTION_CALL is the fallback.
 */
class CallManager(private val context: Context) {

    sealed class Outcome {
        data class Placed(val number: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Keeps the number as given: only spaces/dashes/brackets are removed, "+" and leading zeros are kept. */
    fun sanitize(number: String): String = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        .let { if (it.count { c -> c == '+' } > 1) it.replace("+", "").let { d -> "+$d" } else it }

    @SuppressLint("MissingPermission")
    fun call(number: String): Outcome {
        val n = sanitize(number)
        if (n.filter { it.isDigit() }.length < 3) return Outcome.Failed("رقم غير صالح")
        if (!hasCallPermission()) return Outcome.Failed("صلاحية الاتصال غير ممنوحة")
        val uri = Uri.fromParts("tel", n, null)
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(uri, null)
            return Outcome.Placed(n)
        } catch (e: Exception) {
            Log.w("CallManager", "placeCall failed, falling back to ACTION_CALL", e)
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome.Placed(n)
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "تعذر بدء الاتصال")
        }
    }

    /** Last dialled number from the system call log (needs READ_CALL_LOG, optional). */
    fun lastOutgoingFromCallLog(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ?", arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null }
    }
}
