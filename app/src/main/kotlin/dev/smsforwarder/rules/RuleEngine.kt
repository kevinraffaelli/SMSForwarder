package dev.smsforwarder.rules

import dev.smsforwarder.contacts.ContactsResolver
import dev.smsforwarder.domain.Predicate
import dev.smsforwarder.domain.Rule
import dev.smsforwarder.domain.Sms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.regex.Pattern as JavaPattern

/**
 * Rule engine (Specification §5). Per-rule AND, rules OR'd together.
 * Returns the first matching rule's id (UI display only); the engine
 * forwards at most once per SMS (no duplicate sends).
 */
class RuleEngine {

    /**
     * @return the id of the first matching rule, or null if no rule matched.
     */
    suspend fun evaluate(
        rules: List<Rule>,
        sms: Sms,
        contacts: ContactsResolver,
    ): String? = withContext(Dispatchers.Default) {
        for (rule in rules) {
            if (!rule.enabled) continue
            if (ruleMatches(rule, sms, contacts)) return@withContext rule.id
        }
        null
    }

    private suspend fun ruleMatches(
        rule: Rule,
        sms: Sms,
        contacts: ContactsResolver,
    ): Boolean {
        for (p in rule.predicates) {
            if (!p.enabled) continue
            if (!predicateMatches(p, sms, contacts)) return false
        }
        return true
    }

    private suspend fun predicateMatches(
        p: Predicate,
        sms: Sms,
        contacts: ContactsResolver,
    ): Boolean = when (p) {
        is Predicate.ContainsKeyword -> {
            p.keyword.isNotBlank() && sms.body.contains(p.keyword, ignoreCase = true)
        }
        is Predicate.FromNumber -> {
            normalizeNumber(sms.from) == normalizeNumber(p.number)
        }
        is Predicate.Regex -> {
            if (p.pattern.isBlank()) return false
            val compiled = try {
                JavaPattern.compile(p.pattern)
            } catch (_: Throwable) {
                // Per spec: invalid pattern ⇒ predicate disabled with a UI error;
                // never throws at match time. Match-time result: false.
                return false
            }
            compiled.matcher(sms.body).find()
        }
        is Predicate.SenderName -> {
            val resolved = contacts.displayNameFor(sms.from)
            resolved != null &&
                resolved.trim().equals(p.name.trim(), ignoreCase = true)
        }
        is Predicate.TimeWindow -> {
            val zone = ZoneId.systemDefault()
            val zdt = Instant.ofEpochMilli(sms.timestamp).atZone(zone)
            val dow = zdt.toLocalDate().dayOfWeek.value // ISO 1..7 Mon..Sun
            if (dow !in p.days) return false

            val minutes = zdt.toLocalTime().toSecondOfDay() / 60
            val (start, end) = p.startMinutes to p.endMinutes
            if (start <= end) {
                minutes in start..end
            } else {
                // Crossing midnight ⇒ window wraps to next day.
                minutes >= start || minutes <= end
            }
        }
        is Predicate.ForwardAll -> true
    }

    companion object {
        /** Strip +, spaces, -, () for numeric comparison (Specification §5.3). */
        fun normalizeNumber(raw: String): String =
            raw.filter { it.isDigit() }
    }
}
