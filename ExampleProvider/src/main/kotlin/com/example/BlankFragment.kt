package com.example

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass.
 */
class BlankFragment(private val plugin: ExamplePlugin) : BottomSheetDialogFragment() {

    // Helper function to get a drawable resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    // Helper function to get a string resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    // Generic findView function to find views by name
    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val layoutId = plugin.resources?.getIdentifier("fragment_blank", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val emailInput: android.widget.EditText? = view.findViewByName("emailEditText")
        val passwordInput: android.widget.EditText? = view.findViewByName("passwordEditText")
        val loginBtn: android.widget.Button? = view.findViewByName("loginButton")
        val statusText: android.widget.TextView? = view.findViewByName("statusTextView")

        // Load saved credentials if any
        val prefs = view.context.getSharedPreferences("PhimFitSettings", android.content.Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("email", null)
        val savedPassword = prefs.getString("password", null)

        if (!savedEmail.isNullOrBlank()) {
            emailInput?.setText(savedEmail)
        }
        if (!savedPassword.isNullOrBlank()) {
            passwordInput?.setText(savedPassword)
        }

        loginBtn?.setOnClickListener {
            val email = emailInput?.text?.toString()?.trim() ?: ""
            val password = passwordInput?.text?.toString() ?: ""

            if (email.isEmpty() || password.isEmpty()) {
                statusText?.apply {
                    visibility = View.VISIBLE
                    text = "Vui lòng nhập đầy đủ thông tin"
                }
                return@setOnClickListener
            }

            statusText?.apply {
                visibility = View.VISIBLE
                text = getString("logging_in") ?: "Đang đăng nhập..."
            }
            loginBtn.isEnabled = false

            // Perform login in coroutine
            CoroutineScope(Dispatchers.Main).launch {
                val provider = PhimFitProvider()
                
                val success = provider.login(email, password)
                
                if (success) {
                    statusText?.apply {
                        text = getString("login_success") ?: "Đăng nhập thành công!"
                        setTextColor(android.graphics.Color.GREEN)
                    }
                    view.postDelayed({
                        try {
                            dismiss()
                        } catch (_: Exception) {}
                    }, 1000)
                } else {
                    loginBtn.isEnabled = true
                    statusText?.apply {
                        text = getString("login_failed") ?: "Đăng nhập thất bại"
                        setTextColor(android.graphics.Color.RED)
                    }
                }
            }
        }
    }
}