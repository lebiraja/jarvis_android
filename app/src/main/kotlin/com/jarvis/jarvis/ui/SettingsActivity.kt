package com.jarvis.jarvis.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.jarvis.R
import com.jarvis.jarvis.contacts.NicknameStore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etNickname = findViewById<EditText>(R.id.etNickname)
        val etPhone = findViewById<EditText>(R.id.etPhone)
        val btnSaveAlias = findViewById<Button>(R.id.btnSaveAlias)

        btnSaveAlias.setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (nickname.isNotEmpty() && phone.isNotEmpty()) {
                lifecycleScope.launch {
                    NicknameStore.saveNickname(this@SettingsActivity, nickname, phone)
                    Toast.makeText(this@SettingsActivity, "Saved Alias: $nickname -> $phone", Toast.LENGTH_SHORT).show()
                    etNickname.text.clear()
                    etPhone.text.clear()
                }
            } else {
                Toast.makeText(this, "Please enter both nickname and phone", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
