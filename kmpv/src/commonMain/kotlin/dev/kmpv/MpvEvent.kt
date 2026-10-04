package dev.kmpv

public enum class MpvEndFileReason {
    Eof,
    Stop,
    Quit,
    Error,
    Redirect,
    Unknown,
}

public sealed interface MpvEvent {
    public data object Shutdown : MpvEvent
    public data object StartFile : MpvEvent
    public data object FileLoaded : MpvEvent
    public data object Seek : MpvEvent
    public data object PlaybackRestart : MpvEvent
    public data object QueueOverflow : MpvEvent

    public data class EndFile(
        val reason: MpvEndFileReason,
        val error: MpvError? = null,
    ) : MpvEvent

    public data class PropertyChange(
        val observerId: Long,
        val name: String,
        val value: MpvValue?,
    ) : MpvEvent

    public data class GetPropertyReply(
        val requestId: Long,
        val name: String,
        val error: MpvError?,
        val value: MpvValue?,
    ) : MpvEvent

    public data class SetPropertyReply(
        val requestId: Long,
        val error: MpvError?,
    ) : MpvEvent

    public data class CommandReply(
        val requestId: Long,
        val error: MpvError?,
        val result: MpvNode? = null,
    ) : MpvEvent

    public data class LogMessage(
        val prefix: String,
        val level: String,
        val text: String,
    ) : MpvEvent

    public data class Unknown(
        val eventId: Int,
        val replyUserdata: Long,
        val error: MpvError?,
    ) : MpvEvent
}
