package com.sebrain.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads the real vault and logs only counts: how many notes are still waiting to be indexed,
 * how many chunks there are, and how many carry a header. No note text is logged. Run it by
 * name only, after the app has been opened; it is not part of any test suite.
 */
@RunWith(AndroidJUnit4::class)
class RealVaultIndexCheck {
    @Test
    fun counts() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SeBrainApp
        app.session.withDatabase { db ->
            fun count(sql: String) = db.rawQuery(sql, emptyArray<String>()).use { c -> c.moveToFirst(); c.getInt(0) }
            Log.i(
                "SEBRAIN_EVAL",
                "real vault: notes=${count("SELECT count(*) FROM capture")} " +
                    "pending=${count("SELECT count(*) FROM capture WHERE index_status = 'pending'")} " +
                    "chunks=${count("SELECT count(*) FROM chunk")} " +
                    "withHeader=${count("SELECT count(*) FROM chunk WHERE text LIKE '[%'")} " +
                    "vectors=${count("SELECT count(*) FROM chunk_vec")}",
            )
        }
    }
}
