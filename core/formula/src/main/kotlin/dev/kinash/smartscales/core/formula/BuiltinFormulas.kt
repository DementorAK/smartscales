package dev.kinash.smartscales.core.formula

/**
 * The built-in formula set: equations from published studies (FORMULAS_REVIEW §5), in the same format as the
 * sets people import. It ships inside this module, so tests check exactly what the app uses.
 */
object BuiltinFormulas {
    private const val RESOURCE = "builtin.json"

    val file: FormulaSetFile by lazy {
        val text = BuiltinFormulas::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("Built-in formula set $RESOURCE is missing")
        FormulaSetFile.parse(text)
    }

    val layer: FormulaLayer get() = FormulaLayer(file, builtIn = true)
}
