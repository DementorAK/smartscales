package dev.kinash.smartscales.core.formula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionTest {
    private val numbers = setOf("weight", "height", "age", "sex", "impedance", "waist", "a", "b")
    private val booleans = setOf("male", "female")

    private fun typeOf(name: String) = when (name) {
        in numbers -> ValueType.NUMBER
        in booleans -> ValueType.BOOLEAN
        else -> null
    }

    private val man = mapOf<String, Any?>(
        "weight" to 80.0, "height" to 180.0, "age" to 35.0, "sex" to 1.0, "impedance" to 450.0,
        "male" to true, "female" to false, "waist" to null, "a" to 2.0, "b" to 3.0,
    )

    private fun eval(text: String, values: Map<String, Any?> = man): Any? {
        val expr = Expr.parse(text)
        expr.check(::typeOf)
        return expr.evaluate { values[it] }
    }

    private fun num(text: String, values: Map<String, Any?> = man) = eval(text, values) as Double

    private fun error(text: String): FormulaException =
        assertThrows(FormulaException::class.java) { Expr.parse(text).check(::typeOf) }

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(7.0, num("1 + 2 * 3"), 0.0)
        assertEquals(9.0, num("(1 + 2) * 3"), 0.0)
        assertEquals(-4.0, num("-2^2"), 0.0)
        assertEquals(512.0, num("2^3^2"), 0.0)
        assertEquals(0.5, num("2^-1"), 0.0)
        assertEquals(0.001, num("1e-3"), 0.0)
        assertEquals(0.5, num(".5"), 0.0)
        assertEquals(1.0, num("10 - 6 - 3"), 0.0)
        assertEquals(2.5, num("10 / 2 / 2"), 0.0)
    }

    @Test
    fun wu2015ForTheReviewMan() {
        // FORMULAS_REVIEW §2.4: 180 cm, 80 kg, 35 years, 450 Ω gives 23.6 % fat
        val ffm = num("13.055 + 0.204 * weight + 0.394 * height^2 / impedance - 0.136 * age + 8.125 * sex")
        assertEquals(23.6, (80 - ffm) / 80 * 100, 0.05)
    }

    @Test
    fun conditionsBySex() {
        val boer = "if(male, 0.407 * weight + 0.267 * height - 19.2, 0.252 * weight + 0.473 * height - 48.3)"
        assertEquals(0.407 * 80 + 0.267 * 180 - 19.2, num(boer), 1e-9)
        val woman = man + mapOf("male" to false, "female" to true, "sex" to 0.0)
        assertEquals(0.252 * 80 + 0.473 * 180 - 48.3, num(boer, woman), 1e-9)
    }

    @Test
    fun ifWithSeveralBranches() {
        val zone = "if(age < 18, 1, age < 40, 2, age < 60, 3, 4)"
        assertEquals(2.0, num(zone), 0.0)
        assertEquals(4.0, num(zone, man + ("age" to 70.0)), 0.0)
    }

    @Test
    fun functions() {
        assertEquals(3.0, num("sqrt(9)"), 0.0)
        assertEquals(2.0, num("log10(100)"), 1e-12)
        assertEquals(1.0, num("ln(exp(1))"), 1e-12)
        assertEquals(8.0, num("pow(2, 3)"), 0.0)
        assertEquals(1.0, num("min(3, 1, 2)"), 0.0)
        assertEquals(3.0, num("max(3, 1, 2)"), 0.0)
        assertEquals(23.57, num("round(23.5678, 2)"), 1e-12)
        assertEquals(24.0, num("round(23.5)"), 0.0)
        assertEquals(-24.0, num("round(-23.5)"), 0.0)
        // Half away from zero, not to even, and by the decimal notation
        assertEquals(23.0, num("round(22.5)"), 0.0)
        assertEquals(-23.0, num("round(-22.5)"), 0.0)
        assertEquals(0.13, num("round(0.125, 2)"), 0.0)
        assertEquals(1.01, num("round(1.005, 2)"), 0.0)
        assertEquals(1200.0, num("round(1234, -2)"), 0.0)
        assertEquals(-2.0, num("trunc(-2.7)"), 0.0)
        assertEquals(5.0, num("clamp(7, 0, 5)"), 0.0)
        assertEquals(0.0, num("clamp(-1, 0, 5)"), 0.0)
        assertEquals(3.0, num("abs(-3)"), 0.0)
    }

    @Test
    fun missingValueMakesTheResultMissing() {
        assertNull(eval("waist / height"))
        assertNull(eval("max(waist, 1)"))
        assertNull(eval("waist > 90"))
    }

    @Test
    fun missingValueCanBeWorkedAround() {
        assertEquals(false, eval("has(waist)"))
        assertEquals(true, eval("has(weight)"))
        assertEquals(1.0, num("coalesce(waist, 1)"), 0.0)
        assertEquals(0.0, num("if(has(waist), waist / height, 0)"), 0.0)
        // Decided by one side: false and anything is false, true or anything is true
        assertEquals(false, eval("female and waist > 80"))
        assertEquals(true, eval("male or waist > 80"))
        assertNull(eval("male and waist > 80"))
        // The branch not taken may be missing
        assertEquals(1.0, num("if(male, 1, waist)"), 0.0)
        assertNull(eval("if(waist > 80, 1, 2)"))
    }

    @Test
    fun resultsThatAreNotNumbersAreMissing() {
        assertNull(eval("1 / 0"))
        assertNull(eval("sqrt(-1)"))
        assertNull(eval("ln(0)"))
        assertNull(eval("a / (b - 3)"))
    }

    @Test
    fun comparisonsAndLogic() {
        assertEquals(true, eval("age >= 18 and age <= 75"))
        assertEquals(true, eval("not female"))
        assertEquals(true, eval("male == true"))
        assertEquals(true, eval("sex != 0"))
    }

    @Test
    fun numbersOfAnyKindAndSignedZero() {
        assertEquals(81.0, num("weight + 1", man + ("weight" to 80)), 0.0)
        assertEquals(36.0, num("age + 1", man + ("age" to 35L)), 0.0)
        assertEquals(true, eval("0 * -1 == 0"))
        assertEquals(false, eval("0 * -1 != 0"))
        assertEquals(1.0, num("1."), 0.0)
        assertEquals(1500.0, num("1.5e3"), 0.0)
    }

    @Test
    fun deepNestingIsAnErrorNotACrash() {
        assertTrue(error("(".repeat(5000) + "1" + ")".repeat(5000)).message!!.contains("longer"))
        assertTrue(error("(".repeat(1000) + "1" + ")".repeat(1000)).message!!.contains("deeply"))
        assertTrue(error("-".repeat(1000) + "1").message!!.contains("deeply"))
        assertTrue(error("not ".repeat(500) + "male").message!!.contains("deeply"))
        assertTrue(error("abs(".repeat(500) + "1" + ")".repeat(500)).message!!.contains("deeply"))
        assertTrue(error(List(1500) { "1" }.joinToString("+")).message!!.contains("deeply"))
        // Within the limits
        assertEquals(1.0, num("(".repeat(50) + "1" + ")".repeat(50)), 0.0)
        assertEquals(300.0, num(List(300) { "1" }.joinToString("+")), 0.0)
    }

    @Test
    fun onlyAsciiDigitsAndNames() {
        // Arabic-Indic and full-width digits, and a Cyrillic name
        assertEquals(0, error("٣").position)
        assertEquals(0, error("３").position)
        assertEquals(0, error("вес").position)
    }

    @Test
    fun identifierRuleForFormulaSets() {
        assertTrue(Expr.isValidName("ffm_wu"))
        assertTrue(Expr.isValidName("_tmp2"))
        assertTrue(!Expr.isValidName("2ffm"))
        assertTrue(!Expr.isValidName("вес"))
        assertTrue(!Expr.isValidName("ffm-wu"))
        assertTrue(!Expr.isValidName("sqrt"))
        assertTrue(!Expr.isValidName("and"))
        assertTrue(!Expr.isValidName(""))
    }

    @Test
    fun namesAreCollectedInOrder() {
        val names = Expr.parse("0.394 * height^2 / impedance + height + ffm_wu").names()
        assertEquals(listOf("height", "impedance", "ffm_wu"), names.keys.toList())
        assertEquals(8, names["height"])
    }

    @Test
    fun syntaxErrorsPointAtThePlace() {
        assertEquals(4, error("1 + * 2").position)
        assertEquals(7, error("(1 + 2 ").position)
        assertEquals(2, error("1 # 2").position)
        assertEquals(0, error("").position)
        assertEquals(6, error("1 < 2 < 3").position)
        assertEquals(2, error("1 2").position)
        assertEquals(4, error("1 + and").position)
    }

    @Test
    fun typeErrorsPointAtThePlace() {
        assertEquals(4, error("1 + male").position)
        assertEquals(0, error("weight and male").position)
        assertEquals(0, error("weightt * 2").position)
        assertEquals(0, error("sqrt(1, 2)").position)
        assertEquals(0, error("foo(1)").position)
        assertEquals(0, error("if(male, 1)").position)
        assertEquals(12, error("if(male, 1, true)").position)
        assertEquals(3, error("if(1, 2, 3)").position)
        assertEquals(0, error("sqrt + 1").position)
        assertTrue(error("weightt * 2").message!!.contains("weightt"))
    }
}
