package com.example.emergencyaudiodetection.contact


import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.emergencyaudiodetection.R

class ManageContactsActivity : AppCompatActivity() {

    private lateinit var contact1Field: EditText
    private lateinit var contact2Field: EditText
    private lateinit var contact3Field: EditText
    private lateinit var contact4Field: EditText
    private lateinit var contact5Field: EditText
    private lateinit var saveButton: Button

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_manager)

        contact1Field = findViewById(R.id.contact1EditText)
        contact2Field = findViewById(R.id.contact2EditText)
        contact3Field = findViewById(R.id.contact3EditText)
        contact4Field = findViewById(R.id.contact4EditText)
        contact5Field = findViewById(R.id.contact5EditText)
        saveButton = findViewById(R.id.saveButton)

        prefs = getSharedPreferences("emergency_contacts", MODE_PRIVATE)

        // Pre-fill if already saved
        contact1Field.setText(prefs.getString("contact1", ""))
        contact2Field.setText(prefs.getString("contact2", ""))
        contact3Field.setText(prefs.getString("contact3", ""))
        contact4Field.setText(prefs.getString("contact4", ""))
        contact5Field.setText(prefs.getString("contact5", ""))

        saveButton.setOnClickListener {
            val contact1 = contact1Field.text.toString().trim()
            val contact2 = contact2Field.text.toString().trim()
            val contact3 = contact3Field.text.toString().trim()
            val contact4 = contact4Field.text.toString().trim()
            val contact5 = contact5Field.text.toString().trim()

            val contacts = listOf(contact1, contact2, contact3)

            // Validate contacts: must be either blank or 10 digits
            val invalidContacts = contacts.filter {
                it.isNotEmpty() && !it.matches(Regex("^\\d{10}$"))
            }

            if (invalidContacts.isNotEmpty()) {
                Toast.makeText(this, "Please enter valid 10-digit Indian phone numbers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save to SharedPreferences
            with(prefs.edit()) {
                putString("contact1", contact1)
                putString("contact2", contact2)
                putString("contact3", contact3)
                apply()
            }

            Toast.makeText(this, "Contacts saved successfully", Toast.LENGTH_SHORT).show()
            finish() // close and return to previous activity
        }
    }
}
