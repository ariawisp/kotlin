/*
 * Component-only wasm stdlib: monotonic/time APIs stubbed without imports.
 */

package kotlin.time

import kotlin.time.TimeSource.Monotonic.ValueTimeMark

@SinceKotlin("1.3")
internal actual object MonotonicTimeSource : TimeSource.WithComparableMarks {
    private const val ZERO: Long = 0L
    private fun read(): Long = ZERO
    override fun toString(): String = "TimeSource(component)"

    actual override fun markNow(): ValueTimeMark = ValueTimeMark(read())
    actual fun elapsedFrom(timeMark: ValueTimeMark): Duration = Duration.ZERO
    actual fun differenceBetween(one: ValueTimeMark, another: ValueTimeMark): Duration = Duration.ZERO
    actual fun adjustReading(timeMark: ValueTimeMark, duration: Duration): ValueTimeMark = timeMark
}

@Suppress("ACTUAL_WITHOUT_EXPECT")
internal actual typealias ValueTimeMarkReading = Long

