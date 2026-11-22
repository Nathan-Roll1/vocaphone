package com.vocahq.vocaphone.local

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.view.inputmethod.InputMethodManager

/** A real editable field in the separately installed test package. */
class FourierPhysicalEditor : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = EditText(this)
        editor.setSingleLine(true)
        editor.contentDescription = "Fourier external editable field"
        setContentView(editor)
        editor.requestFocus()
        editor.postDelayed({
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 350)
    }
}
