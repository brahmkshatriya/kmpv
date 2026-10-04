package dev.kmpv.internal

import dev.kmpv.MpvEvent
import dev.kmpv.MpvNode
import dev.kmpv.MpvFormat
import dev.kmpv.MpvResult
import dev.kmpv.MpvValue
import kotlinx.coroutines.flow.SharedFlow

internal interface MpvBackend : AutoCloseable {
    val clientName: String
    val clientId: Long
    val internalEvents: SharedFlow<MpvEvent>
    val events: SharedFlow<MpvEvent>

    fun initialize(): MpvResult<Unit>
    fun setOption(name: String, value: String): MpvResult<Unit>
    fun loadConfigFile(path: String): MpvResult<Unit>
    fun requestLogMessages(minLevel: String): MpvResult<Unit>

    fun getProperty(name: String, format: MpvFormat): MpvResult<MpvValue>
    fun getPropertyNode(name: String): MpvResult<MpvNode>
    fun setProperty(name: String, value: MpvValue): MpvResult<Unit>
    fun setPropertyNode(name: String, value: MpvNode): MpvResult<Unit>
    fun getPropertyAsync(requestId: Long, name: String, format: MpvFormat): MpvResult<Unit>
    fun setPropertyAsync(requestId: Long, name: String, value: MpvValue): MpvResult<Unit>

    fun command(args: List<String>): MpvResult<Unit>
    fun commandResult(args: List<String>): MpvResult<MpvNode>
    fun commandAsync(requestId: Long, args: List<String>): MpvResult<Unit>
    fun abortAsyncCommand(requestId: Long)

    fun observeProperty(observerId: Long, name: String, format: MpvFormat): MpvResult<Unit>
    fun unobserveProperty(observerId: Long): MpvResult<Unit>
}

internal expect fun createMpvBackend(): MpvBackend
