package com.tmeinc.gerrymonitor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    lateinit var username: EditText
    lateinit var password: EditText
    lateinit var clientId: Spinner
    lateinit var login: Button
    lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_login)

        clientId = findViewById(R.id.client_id)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        login = findViewById(R.id.login)
        loading = findViewById(R.id.loading)

        // Create an ArrayAdapter for client_id spinner
        var sel = 0
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        if (GerryService.instance != null) {
            val cl = GerryService.gerryClient["clients"]
            if (cl is Map<*, *>) {
                for (id in cl.keys) {
                    if (id is String) {
                        adapter.add(id)
                        if (id == GerryService.clientID) {
                            sel = adapter.getPosition(id)
                        }
                    }
                }
            }
        }
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Apply the adapter to the spinner
        clientId.adapter = adapter
        clientId.setSelection(sel)

        password.apply {

            setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_DONE ->
                        login.performClick()
                }
                false
            }

            login.setOnClickListener {

                if (GerryService.instance != null) {
                    loading.visibility = View.VISIBLE
                    var cliendId = clientId.selectedItem?.toString()
                    if (cliendId == null) {
                        cliendId = "marcus1"
                    }
                    val obj = mapOf(
                        "clientId" to cliendId,
                        "username" to username.text.toString(),
                        "password" to password.text.toString()
                    )
                    GerryService.instance
                        ?.gerryHandler
                        ?.obtainMessage(GerryService.MSG_GERRY_LOGIN, obj)
                        ?.sendToTarget()
                }
            }
        }
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = Runnable {
        timerRun()
    }

    private fun timerRun() {
        if (GerryService.instance != null) {
            val gerryRun = GerryService.gerryRun
            if (gerryRun >= GerryService.RUN_RUN) {
                Intent(this, GerryMainActivity::class.java).also { intent ->
                    startActivity(intent)
                }
                finish()
            } else {
                if (gerryRun == GerryService.RUN_USER_LOGIN) {
                    if (loading.visibility != View.GONE) {
                        loading.visibility = View.GONE
                        Toast.makeText(this, R.string.prompt_password_error, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                timerHandler.postDelayed(timerRunnable, 500)
            }
        } else {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    override fun onStop() {
        super.onStop()
        timerHandler.removeCallbacksAndMessages(null)
    }

}
