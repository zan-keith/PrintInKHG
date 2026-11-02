package org.example.khgprint

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView
    private lateinit var checkButton: Button
    private lateinit var printButton: Button
    private val pool = Executors.newSingleThreadExecutor()
    private val ipp = IppClient()
    private val pickPdfCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wrap = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = (16 * resources.displayMetrics.density).toInt()
            setPadding(d, d, d, d)
        }
        wrap.addView(column)
        setContentView(wrap)

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 12, 0, 12)
            column.addView(this)
        }
        label("KHG Print Probe — prototype")
        label("Server: davinci.khg.jku.at:631 / printers/P3055")
        label("Connect to KHG-WiFi before testing. Password is not stored.")

        usernameInput = EditText(this).apply {
            hint = "KHG username"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            column.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
        passwordInput = EditText(this).apply {
            hint = "KHG password"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            column.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
        checkButton = Button(this).apply {
            text = "1. Check IPP printer capabilities"
            column.addView(this)
            setOnClickListener { checkPrinter() }
        }
        printButton = Button(this).apply {
            text = "2. Choose PDF to print"
            column.addView(this)
            setOnClickListener { choosePdf() }
        }
        status = TextView(this).apply {
            text = "Ready. First check printer capabilities."
            setTextIsSelectable(true)
            textSize = 15f
            setPadding(0, 18, 0, 0)
            column.addView(this)
        }
    }

    private fun credentials(): Pair<String, String>? {
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            status.text = "Enter your KHG username and password."
            return null
        }
        return user to pass
    }

    private fun busy(value: Boolean) {
        checkButton.isEnabled = !value
        printButton.isEnabled = !value
    }

    private fun checkPrinter() {
        val (user, pass) = credentials() ?: return
        busy(true)
        status.text = "Querying KHG printer through authenticated IPP..."
        pool.execute {
            val text = try {
                val r = ipp.check(user, pass)
                "${r.summary()}\n" +
                    "Printer: ${r.values("printer-name")}\n" +
                    "State (3 = idle): ${r.values("printer-state")}\n" +
                    "Accepting jobs: ${r.values("printer-is-accepting-jobs")}\n" +
                    "Supported document formats: ${r.values("document-format-supported")}\n" +
                    "Authentication: ${r.values("uri-authentication-supported")}\n" +
                    "Security: ${r.values("uri-security-supported")}\n\n" +
                    if (r.isSuccess()) "IPP query succeeded. PDF submission can be tested."
                    else "IPP query was rejected."
            } catch (e: Exception) {
                "Connection failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { status.text = text; busy(false) }
        }
    }

    private fun choosePdf() {
        if (credentials() == null) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(i, pickPdfCode)
    }

    @Deprecated("Uses simple system document picker; migration to ActivityResult API is optional.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPdfCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            AlertDialog.Builder(this)
                .setTitle("Submit print job?")
                .setMessage(
                    "This sends the selected PDF to KHG's real printer and may print all pages. " +
                    "First test with a one-page PDF. Continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Print") { _, _ -> submitPdf(uri) }
                .show()
        }
    }

    private fun submitPdf(uri: Uri) {
        val (user, pass) = credentials() ?: return
        busy(true)
        status.text = "Submitting authenticated IPP Print-Job..."
        pool.execute {
            val text = try {
                val r = ipp.printPdf(user, pass, contentResolver, uri)
                if (r.isSuccess()) {
                    "Job accepted by CUPS. ${r.summary()}\n" +
                        "Job ID: ${r.values("job-id")}\n" +
                        "Job state: ${r.values("job-state")}\n\n" +
                        "Accepted does not mean the pages have physically printed."
                } else {
                    "CUPS rejected print job: ${r.summary()}\n" +
                        "Message: ${r.values("status-message")}"
                }
            } catch (e: Exception) {
                "Print failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { status.text = text; busy(false) }
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
