package dev.kmpv

public data class MpvError(
    val code: Int,
    val message: String,
    val context: String? = null,
)

public class MpvException(public val error: MpvError) : RuntimeException(
    buildString {
        append(error.message)
        error.context?.let { append(" (").append(it).append(')') }
    },
)

public sealed interface MpvResult<out T> {
    public data class Success<T>(val value: T) : MpvResult<T>
    public data class Failure(val error: MpvError) : MpvResult<Nothing>
}

public val MpvResult<*>.isSuccess: Boolean get() = this is MpvResult.Success
public val MpvResult<*>.isFailure: Boolean get() = this is MpvResult.Failure

public fun <T> MpvResult<T>.getOrThrow(): T = when (this) {
    is MpvResult.Success -> value
    is MpvResult.Failure -> throw MpvException(error)
}

public fun <T> MpvResult<T>.getOrNull(): T? = when (this) {
    is MpvResult.Success -> value
    is MpvResult.Failure -> null
}

public fun MpvResult<*>.errorOrNull(): MpvError? = when (this) {
    is MpvResult.Success -> null
    is MpvResult.Failure -> error
}

public fun MpvResult<*>.exceptionOrNull(): MpvException? = errorOrNull()?.let(::MpvException)

public inline fun <T, R> MpvResult<T>.map(transform: (T) -> R): MpvResult<R> = when (this) {
    is MpvResult.Success -> MpvResult.Success(transform(value))
    is MpvResult.Failure -> this
}

public inline fun <T, R> MpvResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (MpvError) -> R,
): R = when (this) {
    is MpvResult.Success -> onSuccess(value)
    is MpvResult.Failure -> onFailure(error)
}

public inline fun <T> MpvResult<T>.onSuccess(action: (T) -> Unit): MpvResult<T> = apply {
    if (this is MpvResult.Success) action(value)
}

public inline fun <T> MpvResult<T>.onFailure(action: (MpvError) -> Unit): MpvResult<T> = apply {
    if (this is MpvResult.Failure) action(error)
}

public inline fun <T> MpvResult<T>.recover(transform: (MpvError) -> T): MpvResult<T> = when (this) {
    is MpvResult.Success -> this
    is MpvResult.Failure -> MpvResult.Success(transform(error))
}

public fun <T> MpvResult<T>.toKotlinResult(): Result<T> = when (this) {
    is MpvResult.Success -> Result.success(value)
    is MpvResult.Failure -> Result.failure(MpvException(error))
}
