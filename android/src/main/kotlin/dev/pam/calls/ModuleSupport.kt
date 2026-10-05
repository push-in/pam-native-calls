package dev.pam.calls

import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

internal fun Map<String, WireValue>.text(key: String): String =
    (get(key) as? WireValue.Text)?.value ?: throw IllegalArgumentException("Missing $key")

internal fun Map<String, WireValue>.text(key: String, fallback: String): String =
    (get(key) as? WireValue.Text)?.value ?: fallback

internal fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
    (get(key) as? WireValue.Integer)?.value ?: fallback

internal fun Map<String, WireValue>.flag(key: String, fallback: Boolean = false): Boolean =
    (get(key) as? WireValue.Flag)?.value ?: fallback

internal fun ModuleCompletion.success(values: Map<String, WireValue> = emptyMap()) =
    complete(ModuleResultStatus.SUCCESS, WireMap.encode(values))

internal fun ModuleCompletion.failure(message: String) =
    complete(ModuleResultStatus.FAILURE, message.toByteArray())
