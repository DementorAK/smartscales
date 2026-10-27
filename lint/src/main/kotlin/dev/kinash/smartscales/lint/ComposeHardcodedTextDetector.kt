package dev.kinash.smartscales.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown

/**
 * Flags string literals passed as user-visible text to composable functions,
 * e.g. `Text("Weight")` or `Icon(..., contentDescription = "Back")`.
 *
 * The standard HardcodedText check only inspects XML layouts, so Compose code needs its own check.
 */
class ComposeHardcodedTextDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            if (!method.hasAnnotation(COMPOSABLE)) return
            if (isInsidePreview(node)) return

            val arguments = context.evaluator.computeArgumentMapping(node, method)
            for ((argument, parameter) in arguments) {
                if (parameter.name !in TEXT_PARAMETERS) continue
                val text = literalText(argument) ?: continue
                if (text.none(Char::isLetter)) continue
                context.report(
                    ISSUE,
                    argument,
                    context.getLocation(argument),
                    "Hardcoded text \"$text\" in composable `${node.methodName ?: method.name}`: use `stringResource` instead",
                )
            }
        }
    }

    /** Literal part of a plain string or string template; null when the argument contains no literal. */
    private fun literalText(argument: UExpression): String? =
        when (val expression = argument.skipParenthesizedExprDown()) {
            is ULiteralExpression -> expression.value as? String
            is UPolyadicExpression -> expression.operands
                .mapNotNull { (it.skipParenthesizedExprDown() as? ULiteralExpression)?.value as? String }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("")
            else -> null
        }

    /** Sample text in @Preview functions is never shipped to users. */
    private fun isInsidePreview(node: UElement): Boolean {
        var method = node.getParentOfType<UMethod>()
        while (method != null) {
            if (method.uAnnotations.any { it.qualifiedName?.endsWith("Preview") == true }) return true
            method = method.getParentOfType<UMethod>()
        }
        return false
    }

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"

        /** Parameters of composables that carry text shown to (or read out for) the user. */
        private val TEXT_PARAMETERS = setOf("text", "contentDescription")

        val ISSUE: Issue = Issue.create(
            id = "ComposeHardcodedText",
            briefDescription = "Hardcoded text in Compose",
            explanation = """
                User-visible text must come from string resources so the app can be translated. \
                Use `stringResource(R.string.…)` instead of a string literal.
                """,
            category = Category.I18N,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(ComposeHardcodedTextDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
