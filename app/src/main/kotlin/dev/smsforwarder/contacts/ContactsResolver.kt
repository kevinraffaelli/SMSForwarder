package dev.smsforwarder.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contacts lookup for the `senderName` predicate (Specification §5.3 and §9).
 *
 * Behavior of the predicate when contacts are unavailable:
 *  - No READ_CONTACTS permission granted, or no matching contact found,
 *    ⇒ predicate evaluates to **false** (the engine treats this as "no
 *    match"), **not** an error. Per §5.3 and AGENTS.md.
 */
class ContactsResolver(private val context: Context) {

    private val resolver get() = context.applicationContext.contentResolver

    @Volatile var availabilitySnapshot: Availability = Availability.Unknown
        private set

    fun hasReadContacts(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        availabilitySnapshot = if (granted) Availability.Granted else Availability.Denied
        return granted
    }

    /**
     * Returns the display name (case-insensitive, trimmed comparison by
     * the engine) for [from], or null if not found or contacts unavailable.
     * Designed to be called on a worker thread (the SmsReceiver coroutine
     * per §9 / AGENTS.md decision).
     */
    suspend fun displayNameFor(from: String): String? = withContext(Dispatchers.IO) {
        if (!hasReadContacts()) {
            return@withContext null
        }
        val normalized = RuleEngineCompanionNormalize(from)
        if (normalized.isEmpty()) {
            return@withContext null
        }
        runCatching { queryDisplayName(normalized) }.getOrNull()
    }

    private fun queryDisplayName(number: String): String? {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(number)
            .build()
        resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    enum class Availability { Unknown, Granted, Denied }
}

// Local copy to avoid cross-package symbol coupling.
private fun RuleEngineCompanionNormalize(raw: String): String =
    raw.filter { it.isDigit() }
