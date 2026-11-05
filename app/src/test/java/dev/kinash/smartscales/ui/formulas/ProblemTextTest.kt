package dev.kinash.smartscales.ui.formulas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kinash.smartscales.core.formula.Problem
import dev.kinash.smartscales.core.formula.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ProblemTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = arrayOf("one", "two", "three")

    @Test
    fun englishTextsAreTheEnginesOwn() {
        for (kind in ProblemKind.entries) {
            val count = Regex("%(\\d)\\$").findAll(kind.english).count()
            val problem = Problem(kind, args.take(count))
            assertEquals(kind.name, problem.english, context.getString(kind.text, *problem.args.toTypedArray()))
        }
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianTextsTakeTheSameArguments() {
        for (kind in ProblemKind.entries) {
            val count = Regex("%(\\d)\\$").findAll(kind.english).count()
            val text = context.getString(kind.text, *args.take(count).toTypedArray())
            args.take(count).forEach { assertTrue("${kind.name}: $text", it in text) }
            assertTrue(kind.name, text != kind.english.format(*args.take(count).toTypedArray()))
        }
    }
}
