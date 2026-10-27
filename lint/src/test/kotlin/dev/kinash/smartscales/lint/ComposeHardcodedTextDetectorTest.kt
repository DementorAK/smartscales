package dev.kinash.smartscales.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ComposeHardcodedTextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ComposeHardcodedTextDetector()

    override fun getIssues(): List<Issue> = listOf(ComposeHardcodedTextDetector.ISSUE)

    // Stubs below replace the Compose and Android APIs, so no SDK is needed
    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    private val composeStubs: TestFile = kotlin(
        """
        package androidx.compose.runtime
        annotation class Composable
        """
    ).indented()

    private val previewStub: TestFile = kotlin(
        """
        package androidx.compose.ui.tooling.preview
        annotation class Preview
        """
    ).indented()

    private val uiStubs: TestFile = kotlin(
        """
        package test.ui
        import androidx.compose.runtime.Composable
        @Composable fun Text(text: String, maxLines: Int = 1) {}
        @Composable fun Icon(icon: Int, contentDescription: String?) {}
        @Composable fun NavHost(startDestination: String) {}
        @Composable fun stringResource(id: Int): String = ""
        fun plainText(text: String) {}
        """
    ).indented()

    fun testLiteralsInTextParametersAreErrors() {
        lint().files(
            composeStubs,
            previewStub,
            uiStubs,
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import test.ui.*

                @Composable
                fun Screen(weight: Double) {
                    Text("Weight")
                    Text(text = "${'$'}weight kg")
                    Icon(1, contentDescription = "Back")
                }
                """
            ).indented(),
        ).run().expect(
            """
            src/test/test.kt:7: Error: Hardcoded text "Weight" in composable Text: use stringResource instead [ComposeHardcodedText]
                Text("Weight")
                     ~~~~~~~~
            src/test/test.kt:8: Error: Hardcoded text " kg" in composable Text: use stringResource instead [ComposeHardcodedText]
                Text(text = "${'$'}weight kg")
                            ~~~~~~~~~~~~
            src/test/test.kt:9: Error: Hardcoded text "Back" in composable Icon: use stringResource instead [ComposeHardcodedText]
                Icon(1, contentDescription = "Back")
                                             ~~~~~~
            3 errors, 0 warnings
            """
        )
    }

    fun testResourcesSymbolsAndOtherParametersAreClean() {
        lint().files(
            composeStubs,
            previewStub,
            uiStubs,
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import test.ui.*

                @Composable
                fun Screen(name: String, weight: Double) {
                    Text(stringResource(1))
                    Text(name)
                    Text("${'$'}weight")
                    Text("•")
                    Text("")
                    Icon(1, contentDescription = null)
                    NavHost(startDestination = "home")
                    plainText("not composable")
                }
                """
            ).indented(),
        ).run().expectClean()
    }

    fun testPreviewsAreIgnored() {
        lint().files(
            composeStubs,
            previewStub,
            uiStubs,
            kotlin(
                """
                package test
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.tooling.preview.Preview
                import test.ui.*

                @Preview
                @Composable
                fun ScreenPreview() {
                    Text("Sample")
                }
                """
            ).indented(),
        ).run().expectClean()
    }
}
