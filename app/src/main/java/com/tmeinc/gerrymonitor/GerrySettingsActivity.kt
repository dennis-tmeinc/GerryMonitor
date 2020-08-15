package com.tmeinc.gerrymonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.preference.DialogPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat


class LogoutDialogPreference(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int
) :
    DialogPreference(context, attrs, defStyleAttr, defStyleRes) {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(
        context,
        attrs,
        defStyleAttr,
        0
    )

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context) : this(context, null)
}

class GerrySettingsActivity : AppCompatActivity() {

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }

        override fun onDisplayPreferenceDialog(preference: Preference?) =
            if (preference is LogoutDialogPreference) {
                val dialogFragment: DialogFragment = object : PreferenceDialogFragmentCompat() {
                    override fun onDialogClosed(positiveResult: Boolean) {
                        if (positiveResult) {
                            // do things
                        }
                    }
                }
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            } else {
                super.onDisplayPreferenceDialog(preference)
            }

        override fun onPreferenceTreeClick(preference: Preference?): Boolean {
            var res = super.onPreferenceTreeClick(preference)
            when (preference?.key) {
                "logout" -> {
                    preference
                        .sharedPreferences
                        .edit()
                        .apply {
                            clear()
                        }.apply()

                    val obj = mapOf(
                        "callback" to {
                            Intent(
                                activity,
                                LoginActivity::class.java
                            ).also { intent ->
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                            }
                        }
                    )
                    GerryService.instance
                        ?.gerryHandler
                        ?.obtainMessage(GerryService.MSG_GERRY_LOGOUT, obj)
                        ?.sendToTarget()
                    return true
                }
                else -> {
                    return res
                }
            }

        }

        override fun onStop() {
            super.onStop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressed()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

}