package dev.kinash.smartscales.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class SmartScalesIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(ComposeHardcodedTextDetector.ISSUE)

    override val api: Int = CURRENT_API

    override val minApi: Int = 14

    override val vendor: Vendor = Vendor(
        vendorName = "SmartScales",
        identifier = "dev.kinash.smartscales:lint",
        feedbackUrl = "https://github.com/DementorAK/smartscales/issues",
    )
}
