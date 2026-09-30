package dev.smsforwarder.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A single forwarder rule (Specification §5.1). A rule matches when all its
 * enabled predicates match (AND-within-rule). Rules combined with OR.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val predicates: List<Predicate>,
) {
    init { require(predicates.isNotEmpty()) { "Rule $id must have ≥1 predicate" } }
}

@Serializable
@JsonClassDiscriminator("type")
sealed class Predicate {
    abstract val enabled: Boolean

    @Serializable
    @SerialName("containsKeyword")
    data class ContainsKeyword(
        val keyword: String,
        override val enabled: Boolean = true,
    ) : Predicate()

    @Serializable
    @SerialName("fromNumber")
    data class FromNumber(
        val number: String,
        override val enabled: Boolean = true,
    ) : Predicate()

    @Serializable
    @SerialName("regex")
    data class Regex(
        val pattern: String,
        override val enabled: Boolean = true,
    ) : Predicate()

    @Serializable
    @SerialName("senderName")
    data class SenderName(
        val name: String,
        override val enabled: Boolean = true,
    ) : Predicate()

    /** ISO weekdays 1..7 (Mon..Sun). end < start ⇒ window wraps to next day. */
    @Serializable
    @SerialName("timeWindow")
    data class TimeWindow(
        val startMinutes: Int,
        val endMinutes: Int,
        val days: Set<Int>,
        override val enabled: Boolean = true,
    ) : Predicate()

    @Serializable
    @SerialName("forwardAll")
    data class ForwardAll(
        override val enabled: Boolean = true,
    ) : Predicate()
}

/** In-memory SMS representation seen by the rule engine (Specification §6 step 1). */
data class Sms(
    val from: String,
    val body: String,
    val timestamp: Long,
)
