package com.example.emergencyaudiodetection

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.os.Looper
import android.os.Handler
import android.telephony.SmsManager
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import androidx.activity.result.contract.ActivityResultContracts
import android.media.AudioRecord
import android.util.Log
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private var isRecording = false
    private lateinit var tfliteInterpreter: Interpreter
    private val emergencyContacts =
        listOf("1234567890") // Replace with logic to fetch from phonebook
    private val smsManager: SmsManager = SmsManager.getDefault()
    private var emergencyDetected = false
    private var handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var userLatitude: Double? = null
    private var userLongitude: Double? = null
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val SAMPLE_RATE = 16000  // 16 kHz for YAMNet
    private val RECORDING_LENGTH = SAMPLE_RATE * 1  // 1 second of audio
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)

        setupPermissionLauncher() // NEW LINE

        recordButton.setOnClickListener {
            if (!isRecording) {
                checkAndRequestPermissions() // UPDATED
            } else {
                stopRecording()
            }
        }

        loadModel()
    }


    private fun setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }

            if (allGranted) {
                startRecording()
            } else {
                Toast.makeText(this, "Permissions Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS
            )
        )
    }


    private fun loadModel() {
        val modelFile = FileUtil.loadMappedFile(this, "emergency_audio_model.tflite")
        tfliteInterpreter = Interpreter(modelFile)
    }


    private fun startRecording() {
        isRecording = true
        updateButtonUI()
        Toast.makeText(this, "Recording Started", Toast.LENGTH_SHORT).show()

        // Simulated audio input and classification
        simulateAudioDetection()
    }

    private fun stopRecording() {
        isRecording = false
        updateButtonUI()
        Toast.makeText(this, "Recording Stopped", Toast.LENGTH_SHORT).show()
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateButtonUI() {
        recordButton.apply {
            text = if (isRecording) "Stop Recording" else "Start Recording"
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isRecording) R.drawable.btn_shape_disabled else R.drawable.btn_shape_enabled
            )
        }
    }


    private fun simulateAudioDetection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission to record audio is required", Toast.LENGTH_SHORT).show()
            return
        }

        val SAMPLE_RATE = 16000
        val RECORD_DURATION = 1 // seconds
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioBuffer = ShortArray(SAMPLE_RATE * RECORD_DURATION)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "AudioRecord failed to initialize", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                audioRecord.startRecording()
                val samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                audioRecord.stop()
                audioRecord.release()

                if (samplesRead > 0) {
                    val floatInput = FloatArray(samplesRead) { i ->
                        audioBuffer[i] / 32767.0f
                    }

                    val prediction = runInference(floatInput) // "emergency" or "non-emergency"

                    runOnUiThread {
                        if (prediction == "emergency") {
                            showConfirmationDialog()
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!userResponded) {
                                    sendEmergencySMS()
                                }
                            }, 10_000)
                        } else {
                            Toast.makeText(this, "No emergency detected", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }





    private fun runInference(audioInput: FloatArray): String {
        val input = arrayOf(audioInput) // Assuming your model input is shape: [1, N]
        val output = Array(1) { FloatArray(2) } // Output for 2 classes: emergency / non-emergency

        try {
            tfliteInterpreter.run(input, output)
            val maxIdx = output[0].indices.maxByOrNull { output[0][it] } ?: -1
            return if (maxIdx == 1) "emergency" else "non-emergency"
        } catch (e: Exception) {
            Log.e("InferenceError", e.message ?: "Unknown error")
            return "error"
        }
    }
    private var userResponded = false

    private fun showConfirmationDialog() {
        userResponded = false
        AlertDialog.Builder(this)
            .setTitle("Emergency Sound Detected")
            .setMessage("Do you need help?")
            .setPositiveButton("Yes, send alert") @androidx.annotation.RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION]) { _, _ ->
                userResponded = true
                sendEmergencySMS()
            }
            .setNegativeButton("No, false alarm") { _, _ ->
                userResponded = true
                Toast.makeText(this, "Alert canceled", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun sendEmergencySMS() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val message = """
                ⚠ URGENT: Possible emergency sound detected near [User].
                Location: https://maps.google.com/?q=${location.latitude},${location.longitude}
                Time: ${SimpleDateFormat("dd-MM-yyyy | HH:mm", Locale.getDefault()).format(Date())}
                Please check on them immediately.
                — Emergency Audio Detection App
            """.trimIndent()

                val smsManager = SmsManager.getDefault()
                val emergencyContacts = listOf("9876543210", "1234567890") // Replace with real contact list

                for (number in emergencyContacts) {
                    smsManager.sendTextMessage(number, null, message, null, null)
                }

                Toast.makeText(this, "Emergency SMS sent", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            }
        }
    }




    private fun startEmergencyCountdown() {
        var seconds = 10
        Toast.makeText(this, "10 seconds to cancel...", Toast.LENGTH_LONG).show()

        handler.postDelayed({
            if (emergencyDetected &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                sendEmergencyMessage()
            } else {
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            }
        }, 10000)

    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun sendEmergencyMessage() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val locationUrl =
                    "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                val message = "Emergency detected (e.g., fire). My location: $locationUrl"

                emergencyContacts.forEach { number ->
                    smsManager.sendTextMessage(number, null, message, null, null)
                }

                Toast.makeText(this, "Emergency SMS sent", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Could not get location", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to access location", Toast.LENGTH_SHORT).show()
        }
    }
}
