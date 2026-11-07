package com.sebrain.diag

import android.util.Log

const val TAG = "SEBRAIN_DIAG"

/** Salida común: logcat con una línea parseable y, opcionalmente, la pantalla. */
object Diag {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun result(test: String, result: String, value: String, note: String = "") {
        emit("RESULT|$test|$result|$value|$note")
    }

    fun info(message: String) = emit(message)

    /** Registra el error real: clase, mensaje, cadena de causas y stack trace. */
    fun error(context: String, t: Throwable) {
        Log.e(TAG, "ERROR $context: ${describe(t)}", t)
        sink?.invoke("ERROR $context: ${describe(t)}")
    }

    fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(6)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
            .replace('\n', ' ')
            .replace('|', '/')

    private fun emit(line: String) {
        Log.i(TAG, line)
        sink?.invoke(line)
    }
}

class Stats(samples: List<Double>) {
    val first = samples.first()
    val avg = samples.average()
    val min = samples.min()
    val max = samples.max()
    val n = samples.size

    override fun toString() =
        "avg=%.2f ms min=%.2f max=%.2f first=%.2f n=%d".format(avg, min, max, first, n)
}

inline fun <T> timedMs(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / 1_000_000.0
}
