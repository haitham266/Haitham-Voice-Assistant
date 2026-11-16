package com.haitham.voiceassistant.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.haitham.voiceassistant.model.Contact
import com.haitham.voiceassistant.model.PhoneEntry

/**
 * Reads names and numbers from the Android Contacts Provider (after READ_CONTACTS
 * is granted) and keeps an in-memory cache that is refreshed when contacts change.
 * Numbers are kept exactly as stored by the user.
 */
class ContactsRepository(private val context: Context) {

    @Volatile private var cache: List<Contact>? = null
    private var observer: ContentObserver? = null
    var onChanged: (() -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun contacts(): List<Contact> = cache ?: load().also { cache = it }

    fun invalidate() { cache = null }

    fun startObserving() {
        if (observer != null || !hasPermission()) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { invalidate(); onChanged?.invoke() }
        }.also {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, it)
        }
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun load(): List<Contact> {
        if (!hasPermission()) return emptyList()
        val byId = LinkedHashMap<Long, MutableList<PhoneEntry>>()
        val names = HashMap<Long, String>()
        val starred = HashMap<Long, Boolean>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(projection[0])
            val iName = c.getColumnIndexOrThrow(projection[1])
            val iNum = c.getColumnIndexOrThrow(projection[2])
            val iType = c.getColumnIndexOrThrow(projection[3])
            val iLabel = c.getColumnIndexOrThrow(projection[4])
            val iPrimary = c.getColumnIndexOrThrow(projection[5])
            val iStar = c.getColumnIndexOrThrow(projection[6])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val number = c.getString(iNum)?.trim().orEmpty()
                if (number.isEmpty()) continue
                names[id] = c.getString(iName).orEmpty()
                starred[id] = c.getInt(iStar) == 1
                val list = byId.getOrPut(id) { mutableListOf() }
                val digits = number.filter { it.isDigit() }
                if (list.none { it.number.filter { ch -> ch.isDigit() } == digits }) {
                    list += PhoneEntry(number, c.getInt(iType), c.getString(iLabel), c.getInt(iPrimary) == 1)
                }
            }
        }
        val calls = callCounts()
        return byId.map { (id, phones) ->
            val count = phones.sumOf { calls[ContactMatcher.phoneKey(it.number)] ?: 0 }
            Contact(id, names[id].orEmpty(), phones, starred[id] ?: false, count)
        }
    }

    /** Outgoing call counts per number (last 9 digits), only if READ_CALL_LOG was granted. */
    private fun callCounts(): Map<String, Int> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val out = HashMap<String, Int>()
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.NUMBER),
                "${android.provider.CallLog.Calls.TYPE} = ?", arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val k = ContactMatcher.phoneKey(c.getString(0).orEmpty())
                    if (k.isNotEmpty()) out[k] = (out[k] ?: 0) + 1
                }
            }
        } catch (_: Exception) { }
        return out
    }
}
