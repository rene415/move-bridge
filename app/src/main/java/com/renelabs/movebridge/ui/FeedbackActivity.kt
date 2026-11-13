package com.renelabs.movebridge.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.renelabs.movebridge.BuildConfig
import com.renelabs.movebridge.Crash
import com.renelabs.movebridge.R
import com.renelabs.movebridge.Report

/**
 * Feedback and bug reports for beta testers. Nothing is sent by the app itself: this screen
 * builds a text the user can read in full, and hands it to whatever they choose (the share sheet,
 * a GitHub issue, or the clipboard).
 */
class FeedbackActivity : Activity() {
    private lateinit var kinds: RadioGroup
    private lateinit var note: EditText
    private lateinit var withDetails: CheckBox
    private lateinit var preview: TextView
    private val kindNames by lazy { resources.getStringArray(R.array.feedback_kinds) }
    private val hints by lazy { resources.getStringArray(R.array.feedback_hints) }
    private var kind = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)
        kinds = findViewById(R.id.feedbackKinds)
        note = findViewById(R.id.feedbackNote)
        withDetails = findViewById(R.id.feedbackDetails)
        preview = findViewById(R.id.feedbackPreview)
        kind = savedInstanceState?.getInt(KEY_KIND) ?: intent.getIntExtra(EXTRA_KIND, 0)
        buildKinds()
        bindButtons()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(KEY_KIND, kind)
    }

    /** One radio button per kind of feedback; picking one changes the question in the text box. */
    private fun buildKinds() {
        kindNames.forEachIndexed { i, name ->
            val button = RadioButton(this).apply {
                text = name
                textSize = 15f
                setTextColor(getColor(R.color.text))
                setOnClickListener { select(i) }
            }
            kinds.addView(button)
            if (i == kind) button.isChecked = true
        }
        select(kind)
    }

    private fun select(i: Int) {
        kind = i
        note.hint = hints[i]
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.feedbackSend).setOnClickListener { share() }
        findViewById<Button>(R.id.feedbackCopy).setOnClickListener { copy() }
        findViewById<Button>(R.id.feedbackGithub).apply {
            visibility = if (BuildConfig.REPO_URL.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { openIssue() }
        }
        findViewById<TextView>(R.id.feedbackPreviewToggle).setOnClickListener { togglePreview() }
    }

    private fun report() = Report.build(this, kindNames[kind], note.text.toString(), withDetails.isChecked)

    private fun togglePreview() {
        val open = preview.visibility != View.VISIBLE
        if (open) preview.text = Report.details(this)
        preview.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback_subject, kindNames[kind]))
            .putExtra(Intent.EXTRA_TEXT, report())
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.feedback_send))) }
        sent()
    }

    private fun copy() {
        val clip = ClipData.newPlainText(getString(R.string.feedback_title), report())
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.feedback_copied, Toast.LENGTH_SHORT).show()
        sent()
    }

    /** Opens a new issue on the project's GitHub page with the report filled in (needs a GitHub account). */
    private fun openIssue() {
        val url = Uri.parse(BuildConfig.REPO_URL + "/issues/new").buildUpon()
            .appendQueryParameter("title", kindNames[kind])
            .appendQueryParameter("body", report().take(MAX_ISSUE_CHARS))
            .build()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onFailure { Toast.makeText(this, R.string.feedback_no_browser, Toast.LENGTH_LONG).show() }
        sent()
    }

    /** A crash that went out with a report doesn't need to be offered again. */
    private fun sent() {
        if (withDetails.isChecked) Crash.clear(this)
    }

    companion object {
        /** Index into R.array.feedback_kinds to preselect. */
        const val EXTRA_KIND = "kind"
        /** "The app closed or froze" in R.array.feedback_kinds. */
        const val KIND_CRASH = 5
        private const val KEY_KIND = "kind"
        /** Browsers and GitHub cut very long links: keep the prefilled issue well under that. */
        private const val MAX_ISSUE_CHARS = 5000
    }
}
