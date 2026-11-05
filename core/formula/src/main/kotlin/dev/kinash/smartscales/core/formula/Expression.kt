package dev.kinash.smartscales.core.formula

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.truncate

/** Value types of the formula language. */
enum class ValueType { NUMBER, BOOLEAN }

/** A mistake in a formula, at [position] (0-based character index in the text). */
class FormulaException(val problem: Problem, val position: Int) : Exception("${problem.english} at ${position + 1}") {
    constructor(kind: ProblemKind, position: Int, vararg args: Any) : this(Problem(kind, *args), position)

    /** In English. */
    val reason: String get() = problem.english
}

/**
 * An expression of the formula language, parsed from text.
 *
 * Syntax: numbers (`0.394`, `1e-3`), names (`weight`, `ffm_wu`), `+ - * /`, `^` (power, right-associative,
 * above unary minus: `-2^2` is −4), comparisons `< <= > >= == !=`, `and`, `or`, `not`, `true`, `false`,
 * parentheses and function calls ([Functions]).
 *
 * Values are numbers and booleans. A missing value (a field not entered, an impedance the scale did not
 * send) is `null` and makes the result missing, not zero; the same holds for results that are not numbers
 * (division by zero, square root of a negative). `if`, `and`, `or`, `coalesce` and `has` look at their
 * arguments only as far as needed, so they can work around missing values.
 */
sealed interface Expr {
    /**
     * Where the expression starts in the text, for error messages; for a parenthesised expression, its first
     * token inside the parentheses.
     */
    val position: Int

    data class Number(val value: Double, override val position: Int) : Expr
    data class Bool(val value: Boolean, override val position: Int) : Expr
    data class Name(val name: String, override val position: Int) : Expr
    data class Unary(val op: String, val operand: Expr, override val position: Int) : Expr
    /** [opPosition] is where the operator is, for errors about the operation itself. */
    data class Binary(val op: String, val left: Expr, val right: Expr, val opPosition: Int) : Expr {
        override val position: Int get() = left.position
    }
    data class Call(val function: String, val args: List<Expr>, override val position: Int) : Expr

    /** Every name used, with the position of its first use. */
    fun names(): Map<String, Int> = LinkedHashMap<String, Int>().also { collectNames(this, it) }

    /**
     * The type of the expression, with names typed by [typeOf] (null for an unknown name). Throws
     * [FormulaException] at the first wrong name, argument or operand.
     */
    fun check(typeOf: (String) -> ValueType?): ValueType = TypeChecker(typeOf).check(this)

    /** The value: a [Double], a [Boolean], or null when missing. [valueOf] gives the value of each name. */
    fun evaluate(valueOf: (String) -> Any?): Any? = Evaluator(valueOf).eval(this)

    companion object {
        /** What a name in a formula looks like; identifiers of a formula set follow the same rule. */
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Words of the language that cannot be names. */
        val RESERVED: Set<String> get() = Functions.names + setOf("and", "or", "not", "true", "false")

        /** Whether [id] can be used as a name in formulas: [NAME] and not [RESERVED]. */
        fun isValidName(id: String): Boolean = NAME.matches(id) && id !in RESERVED

        /** Longer formulas are rejected: no real one comes close, and a huge one could exhaust the stack. */
        const val MAX_LENGTH = 4000

        /** Deepest nesting of parentheses, calls, signs and `not`, and deepest tree of operations. */
        const val MAX_DEPTH = 100

        fun parse(text: String): Expr {
            if (text.length > MAX_LENGTH) throw FormulaException(ProblemKind.TOO_LONG, MAX_LENGTH, MAX_LENGTH)
            val expr = Parser(text).parseAll()
            checkDepth(expr)
            return expr
        }
    }
}

/**
 * A long chain such as `1 + 1 + … + 1` is parsed without nesting but builds a deep tree; checking, evaluating
 * and collecting names walk it recursively, so its depth is limited too. The walk here is iterative.
 */
private fun checkDepth(root: Expr) {
    val stack = ArrayDeque<Pair<Expr, Int>>()
    stack.addLast(root to 1)
    while (stack.isNotEmpty()) {
        val (expr, depth) = stack.removeLast()
        if (depth > Expr.MAX_DEPTH * 4) throw FormulaException(ProblemKind.TOO_DEEP, expr.position)
        when (expr) {
            is Expr.Unary -> stack.addLast(expr.operand to depth + 1)
            is Expr.Binary -> {
                stack.addLast(expr.left to depth + 1)
                stack.addLast(expr.right to depth + 1)
            }
            is Expr.Call -> expr.args.forEach { stack.addLast(it to depth + 1) }
            is Expr.Number, is Expr.Bool, is Expr.Name -> Unit
        }
    }
}

private fun collectNames(expr: Expr, into: MutableMap<String, Int>) {
    when (expr) {
        is Expr.Name -> into.putIfAbsent(expr.name, expr.position)
        is Expr.Unary -> collectNames(expr.operand, into)
        is Expr.Binary -> {
            collectNames(expr.left, into)
            collectNames(expr.right, into)
        }
        is Expr.Call -> expr.args.forEach { collectNames(it, into) }
        is Expr.Number, is Expr.Bool -> Unit
    }
}

// ---- Lexer and parser ----

private class Token(val kind: Kind, val text: String, val position: Int) {
    enum class Kind { NUMBER, NAME, SYMBOL, END }
}

private fun tokenize(text: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            // ASCII only: other scripts' digits look like numbers but are not read as such
            c in '0'..'9' || (c == '.' && text.getOrNull(i + 1)?.let { it in '0'..'9' } == true) -> {
                val match = NUMBER.matchAt(text, i) ?: throw FormulaException(ProblemKind.BAD_NUMBER, i)
                tokens += Token(Token.Kind.NUMBER, match.value, i)
                i += match.value.length
            }
            c.isNameStart() -> {
                val start = i
                while (i < text.length && (text[i].isNameStart() || text[i] in '0'..'9')) i++
                tokens += Token(Token.Kind.NAME, text.substring(start, i), start)
            }
            else -> {
                val symbol = SYMBOLS.firstOrNull { text.startsWith(it, i) }
                    ?: throw FormulaException(ProblemKind.UNEXPECTED_CHARACTER, i, c)
                tokens += Token(Token.Kind.SYMBOL, symbol, i)
                i += symbol.length
            }
        }
    }
    tokens += Token(Token.Kind.END, "", text.length)
    return tokens
}

private fun Char.isNameStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_'

/** `12`, `12.5`, `12.`, `.5`, with an optional exponent; ASCII digits only. */
private val NUMBER = Regex("""(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

// Longer symbols first, so "<=" is not read as "<"
private val SYMBOLS = listOf("<=", ">=", "==", "!=", "<", ">", "+", "-", "*", "/", "^", "(", ")", ",")

private val COMPARISONS = setOf("<", "<=", ">", ">=", "==", "!=")

/** Precedence, lowest first: `or`, `and`, `not`, comparison, `+ -`, `* /`, unary minus, `^`. */
private class Parser(text: String) {
    private val tokens = tokenize(text)
    private var at = 0
    private var depth = 0

    private val current get() = tokens[at]

    /** Runs [parse] one nesting level deeper, failing with a message instead of a stack overflow. */
    private inline fun <T> nested(position: Int, parse: () -> T): T {
        if (++depth > Expr.MAX_DEPTH) throw FormulaException(ProblemKind.TOO_DEEP, position)
        try {
            return parse()
        } finally {
            depth--
        }
    }

    fun parseAll(): Expr {
        if (current.kind == Token.Kind.END) throw FormulaException(ProblemKind.EMPTY, 0)
        val expr = or()
        if (current.kind != Token.Kind.END) throw FormulaException(ProblemKind.UNEXPECTED, current.position, current.text)
        return expr
    }

    private fun isWord(word: String) = current.kind == Token.Kind.NAME && current.text == word
    private fun isSymbol(symbol: String) = current.kind == Token.Kind.SYMBOL && current.text == symbol

    private fun or(): Expr {
        var left = and()
        while (isWord("or")) {
            val position = current.position
            at++
            left = Expr.Binary("or", left, and(), position)
        }
        return left
    }

    private fun and(): Expr {
        var left = not()
        while (isWord("and")) {
            val position = current.position
            at++
            left = Expr.Binary("and", left, not(), position)
        }
        return left
    }

    private fun not(): Expr {
        if (!isWord("not")) return comparison()
        val position = current.position
        at++
        return Expr.Unary("not", nested(position) { not() }, position)
    }

    /** Not chained: `a < b < c` is an error rather than a surprise. */
    private fun comparison(): Expr {
        val left = additive()
        if (current.kind != Token.Kind.SYMBOL || current.text !in COMPARISONS) return left
        val op = current
        at++
        val right = additive()
        if (current.kind == Token.Kind.SYMBOL && current.text in COMPARISONS) {
            throw FormulaException(ProblemKind.CHAINED_COMPARISON, current.position)
        }
        return Expr.Binary(op.text, left, right, op.position)
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (isSymbol("+") || isSymbol("-")) {
            val op = current
            at++
            left = Expr.Binary(op.text, left, multiplicative(), op.position)
        }
        return left
    }

    private fun multiplicative(): Expr {
        var left = unary()
        while (isSymbol("*") || isSymbol("/")) {
            val op = current
            at++
            left = Expr.Binary(op.text, left, unary(), op.position)
        }
        return left
    }

    private fun unary(): Expr {
        if (isSymbol("-") || isSymbol("+")) {
            val op = current
            at++
            val operand = nested(op.position) { unary() }
            return if (op.text == "-") Expr.Unary("-", operand, op.position) else operand
        }
        return power()
    }

    private fun power(): Expr {
        val base = primary()
        if (!isSymbol("^")) return base
        val position = current.position
        at++
        // Right-associative, and the exponent may carry its own sign: 2^-1
        return Expr.Binary("^", base, nested(position) { unary() }, position)
    }

    private fun primary(): Expr {
        val token = current
        return when (token.kind) {
            Token.Kind.NUMBER -> {
                at++
                Expr.Number(token.text.toDouble(), token.position)
            }
            Token.Kind.NAME -> {
                at++
                when {
                    token.text == "true" -> Expr.Bool(true, token.position)
                    token.text == "false" -> Expr.Bool(false, token.position)
                    token.text in KEYWORDS -> throw FormulaException(ProblemKind.UNEXPECTED, token.position, token.text)
                    isSymbol("(") -> call(token)
                    else -> Expr.Name(token.text, token.position)
                }
            }
            Token.Kind.SYMBOL -> {
                if (token.text != "(") throw FormulaException(ProblemKind.UNEXPECTED, token.position, token.text)
                at++
                val inner = nested(token.position) { or() }
                expect(")")
                inner
            }
            Token.Kind.END -> throw FormulaException(ProblemKind.ENDS_EARLY, token.position)
        }
    }

    private fun call(name: Token): Expr = nested(name.position) {
        at++ // (
        val args = ArrayList<Expr>()
        if (!isSymbol(")")) {
            args += or()
            while (isSymbol(",")) {
                at++
                args += or()
            }
        }
        expect(")")
        Expr.Call(name.text, args, name.position)
    }

    private fun expect(symbol: String) {
        if (!isSymbol(symbol)) {
            throw if (current.kind == Token.Kind.END) {
                FormulaException(ProblemKind.EXPECTED_BUT_END, current.position, symbol)
            } else {
                FormulaException(ProblemKind.EXPECTED_BUT, current.position, symbol, current.text)
            }
        }
        at++
    }

    private companion object {
        val KEYWORDS = setOf("and", "or", "not")
    }
}

// ---- Functions ----

/** Built-in functions; names are reserved and cannot be used for inputs, parameters or indicators. */
object Functions {
    /** Number functions with a fixed number of arguments. */
    private val numeric: Map<String, Pair<IntRange, (List<Double>) -> Double>> = mapOf(
        "abs" to (1..1 to { a -> abs(a[0]) }),
        "sqrt" to (1..1 to { a -> sqrt(a[0]) }),
        "ln" to (1..1 to { a -> ln(a[0]) }),
        "log10" to (1..1 to { a -> log10(a[0]) }),
        "exp" to (1..1 to { a -> exp(a[0]) }),
        "floor" to (1..1 to { a -> floor(a[0]) }),
        "ceil" to (1..1 to { a -> ceil(a[0]) }),
        "trunc" to (1..1 to { a -> truncate(a[0]) }),
        "pow" to (2..2 to { a -> a[0].pow(a[1]) }),
        "round" to (1..2 to { a -> roundTo(a[0], a.getOrElse(1) { 0.0 }) }),
        "clamp" to (3..3 to { a -> a[0].coerceIn(minOf(a[1], a[2]), maxOf(a[1], a[2])) }),
        "min" to (1..Int.MAX_VALUE to { a -> a.min() }),
        "max" to (1..Int.MAX_VALUE to { a -> a.max() }),
    )

    /** Functions that decide themselves which arguments to evaluate. */
    private val special = setOf("if", "coalesce", "has")

    val names: Set<String> = numeric.keys + special

    internal fun numeric(name: String) = numeric[name]

    internal fun isSpecial(name: String) = name in special

    /**
     * Half away from zero, to [digits] decimals (negative digits round to tens, hundreds…), on the decimal
     * notation of [x]: `1.005` is 1.00499… in binary, but rounds to 1.01 as written.
     */
    private fun roundTo(x: Double, digits: Double): Double {
        if (!x.isFinite()) return x
        return BigDecimal(x.toString()).setScale(digits.toInt().coerceIn(-MAX_DIGITS, MAX_DIGITS), RoundingMode.HALF_UP).toDouble()
    }

    private const val MAX_DIGITS = 15
}

// ---- Type checking ----

private class TypeChecker(private val typeOf: (String) -> ValueType?) {
    fun check(expr: Expr): ValueType = when (expr) {
        is Expr.Number -> ValueType.NUMBER
        is Expr.Bool -> ValueType.BOOLEAN
        is Expr.Name -> {
            if (expr.name in Functions.names) throw FormulaException(ProblemKind.FUNCTION_NOT_CALLED, expr.position, expr.name)
            typeOf(expr.name) ?: throw FormulaException(ProblemKind.UNKNOWN_NAME, expr.position, expr.name)
        }
        is Expr.Unary -> when (expr.op) {
            "not" -> expect(expr.operand, ValueType.BOOLEAN).let { ValueType.BOOLEAN }
            else -> expect(expr.operand, ValueType.NUMBER).let { ValueType.NUMBER }
        }
        is Expr.Binary -> binary(expr)
        is Expr.Call -> call(expr)
    }

    private fun binary(expr: Expr.Binary): ValueType = when (expr.op) {
        "and", "or" -> {
            expect(expr.left, ValueType.BOOLEAN)
            expect(expr.right, ValueType.BOOLEAN)
            ValueType.BOOLEAN
        }
        "==", "!=" -> {
            val left = check(expr.left)
            if (check(expr.right) != left) throw FormulaException(ProblemKind.COMPARE_TYPES, expr.opPosition)
            ValueType.BOOLEAN
        }
        "<", "<=", ">", ">=" -> {
            expect(expr.left, ValueType.NUMBER)
            expect(expr.right, ValueType.NUMBER)
            ValueType.BOOLEAN
        }
        else -> {
            expect(expr.left, ValueType.NUMBER)
            expect(expr.right, ValueType.NUMBER)
            ValueType.NUMBER
        }
    }

    private fun call(expr: Expr.Call): ValueType {
        val name = expr.function
        Functions.numeric(name)?.let { (arity, _) ->
            if (expr.args.size !in arity) throw FormulaException(arityProblem(name, arity), expr.position)
            expr.args.forEach { expect(it, ValueType.NUMBER) }
            return ValueType.NUMBER
        }
        return when (name) {
            "if" -> {
                // if(condition, value, [condition, value]..., otherwise)
                val args = expr.args
                if (args.size < 3 || args.size % 2 == 0) {
                    throw FormulaException(ProblemKind.IF_ARGUMENTS, expr.position)
                }
                val type = check(args[1])
                for (i in args.indices) {
                    when {
                        i == args.lastIndex -> expect(args[i], type)
                        i % 2 == 0 -> expect(args[i], ValueType.BOOLEAN)
                        else -> expect(args[i], type)
                    }
                }
                type
            }
            "coalesce" -> {
                if (expr.args.isEmpty()) throw FormulaException(ProblemKind.COALESCE_ARGUMENTS, expr.position)
                val type = check(expr.args[0])
                expr.args.drop(1).forEach { expect(it, type) }
                type
            }
            "has" -> {
                if (expr.args.size != 1) throw FormulaException(ProblemKind.HAS_ARGUMENTS, expr.position)
                check(expr.args[0])
                ValueType.BOOLEAN
            }
            else -> throw FormulaException(ProblemKind.UNKNOWN_FUNCTION, expr.position, name)
        }
    }

    private fun expect(expr: Expr, type: ValueType) {
        if (check(expr) != type) {
            throw FormulaException(if (type == ValueType.NUMBER) ProblemKind.EXPECTED_NUMBER else ProblemKind.EXPECTED_CONDITION, expr.position)
        }
    }

    private fun arityProblem(name: String, arity: IntRange) = when {
        arity.first == arity.last && arity.first == 1 -> Problem(ProblemKind.ARGUMENTS_ONE, name)
        arity.first == arity.last -> Problem(ProblemKind.ARGUMENTS_EXACTLY, name, arity.first)
        arity.last == Int.MAX_VALUE -> Problem(ProblemKind.ARGUMENTS_AT_LEAST, name, arity.first)
        else -> Problem(ProblemKind.ARGUMENTS_RANGE, name, arity.first, arity.last)
    }
}

// ---- Evaluation ----

private class Evaluator(private val valueOf: (String) -> Any?) {
    fun eval(expr: Expr): Any? = when (expr) {
        is Expr.Number -> expr.value
        is Expr.Bool -> expr.value
        is Expr.Name -> when (val value = valueOf(expr.name)) {
            null -> null
            is Number -> finite(value.toDouble())
            is Boolean -> value
            else -> error("Value of '${expr.name}' is neither a number nor a boolean: $value")
        }
        is Expr.Unary -> when (expr.op) {
            "not" -> (eval(expr.operand) as Boolean?)?.not()
            else -> (eval(expr.operand) as Double?)?.let { -it }
        }
        is Expr.Binary -> binary(expr)
        is Expr.Call -> call(expr)
    }

    private fun binary(expr: Expr.Binary): Any? {
        when (expr.op) {
            // Decided by one side when it is enough, even if the other is missing
            "and" -> {
                val left = eval(expr.left) as Boolean?
                if (left == false) return false
                val right = eval(expr.right) as Boolean?
                return if (right == false) false else if (left == null || right == null) null else true
            }
            "or" -> {
                val left = eval(expr.left) as Boolean?
                if (left == true) return true
                val right = eval(expr.right) as Boolean?
                return if (right == true) true else if (left == null || right == null) null else false
            }
        }
        val left = eval(expr.left) ?: return null
        val right = eval(expr.right) ?: return null
        return when (expr.op) {
            // As numbers when they are numbers, so -0.0 equals 0.0
            "==" -> sameValue(left, right)
            "!=" -> !sameValue(left, right)
            "<" -> (left as Double) < (right as Double)
            "<=" -> (left as Double) <= (right as Double)
            ">" -> (left as Double) > (right as Double)
            ">=" -> (left as Double) >= (right as Double)
            "+" -> finite((left as Double) + (right as Double))
            "-" -> finite((left as Double) - (right as Double))
            "*" -> finite((left as Double) * (right as Double))
            "/" -> finite((left as Double) / (right as Double))
            "^" -> finite((left as Double).pow(right as Double))
            else -> error("Unknown operator ${expr.op}")
        }
    }

    private fun call(expr: Expr.Call): Any? {
        Functions.numeric(expr.function)?.let { (_, function) ->
            val args = expr.args.map { eval(it) as Double? ?: return null }
            return finite(function(args))
        }
        return when (expr.function) {
            "if" -> {
                val args = expr.args
                var i = 0
                while (i < args.lastIndex) {
                    when (eval(args[i]) as Boolean?) {
                        true -> return eval(args[i + 1])
                        null -> return null
                        false -> i += 2
                    }
                }
                eval(args.last())
            }
            "coalesce" -> expr.args.firstNotNullOfOrNull { eval(it) }
            "has" -> eval(expr.args[0]) != null
            else -> error("Unknown function ${expr.function}")
        }
    }

    /** Numbers compare as primitives, where -0.0 equals 0.0 (boxed `equals` says they differ). */
    private fun sameValue(left: Any, right: Any): Boolean {
        if (left is Double && right is Double) {
            val l: Double = left
            val r: Double = right
            return l == r
        }
        return left == right
    }

    /** NaN and infinity are not results a person can use: they count as missing. */
    private fun finite(value: Any): Any? = if (value is Double && !value.isFinite()) null else value
}
