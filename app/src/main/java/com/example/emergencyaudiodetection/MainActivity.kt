package com.example.emergencyaudiodetection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var manageContactsButton: Button
    private var isRecording = false
    private lateinit var yamnetInterpreter: Interpreter
    private lateinit var annInterpreter: Interpreter
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val smsManager: SmsManager = SmsManager.getDefault()
    private var userResponded = false

    private val SAMPLE_RATE = 16000
    private val RECORD_DURATION_SEC = 1
    private val AUDIO_LENGTH = SAMPLE_RATE * RECORD_DURATION_SEC
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        manageContactsButton = findViewById(R.id.manageContactsButton)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupPermissionLauncher()
        loadModels()

        recordButton.setOnClickListener {
            if (!isRecording) {
                checkAndRequestPermissions()
            } else {
                stopRecording()
            }
        }

        manageContactsButton.setOnClickListener {
            startActivity(Intent(this, ContactManagerActivity::class.java))
        }
    }

    private fun setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) startRecording()
            else Toast.makeText(this, "Permissions Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
            )
        )
    }

    private fun loadModels() {
        val yamnetModel = FileUtil.loadMappedFile(this, "YamNet.tflite")
        val annModel = FileUtil.loadMappedFile(this, "emergency_audio_model.tflite")
        yamnetInterpreter = Interpreter(yamnetModel)
        annInterpreter = Interpreter(annModel)
    }

    private fun startRecording() {
        isRecording = true
        updateButtonUI()
        Toast.makeText(this, "Recording Started", Toast.LENGTH_SHORT).show()
        handler.post(audioRunnable)
    }

    private fun stopRecording() {
        isRecording = false
        updateButtonUI()
        handler.removeCallbacks(audioRunnable)
        Toast.makeText(this, "Recording Stopped", Toast.LENGTH_SHORT).show()
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

    private val audioRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                processAudio()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun processAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val requiredBufferSize = maxOf(minBufferSize, AUDIO_LENGTH * 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                requiredBufferSize
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Microphone access denied: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "AudioRecord initialization failed", Toast.LENGTH_SHORT).show()
            return
        }

        val audioBuffer = ShortArray(AUDIO_LENGTH)

        Thread {
            try {
                audioRecord.startRecording()
                val read = audioRecord.read(audioBuffer, 0, AUDIO_LENGTH)
                audioRecord.stop()
                audioRecord.release()

                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> audioBuffer[i] / 32767.0f }
                    Log.d("AudioDebug", "Read $read samples")
                    Log.d("AudioDebug", "First 10 samples: ${floatBuffer.take(10).joinToString()}")

                    val prediction = runInference(floatBuffer)

                    runOnUiThread {
                        if (prediction == "emergency") {
                            showConfirmationDialog()
                            handler.postDelayed({
                                if (!userResponded && checkLocationPermissions()) {
                                    try {
                                        sendEmergencySMS()
                                    } catch (e: SecurityException) {
                                        Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }, 10_000)
                        } else {
                            Toast.makeText(this, "No emergency detected", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Recording error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runInference(audioInput: FloatArray): String {
        return try {
            Log.d("DEBUG", "Inside runInference()")
            val yamnetInput = arrayOf(audioInput.copyOf(15360))
            val embeddingOutput = Array(1) { FloatArray(1024) }
            yamnetInterpreter.run(yamnetInput, embeddingOutput)

            val classifierInput = arrayOf(embeddingOutput[0])
            val classifierOutput = Array(1) { FloatArray(1) }

            annInterpreter.run(classifierInput, classifierOutput)

            val emergencyConfidence = classifierOutput[0][0]
            Log.d("ConfidenceScore", "Emergency confidence: $emergencyConfidence")

            if (emergencyConfidence > 0.5f) "emergency" else "non-emergency"
        } catch (e: Exception) {
            Log.e("InferenceError", e.message ?: "Unknown inference error")
            "error"
        }
    }

    private fun showConfirmationDialog() {
        userResponded = false
        AlertDialog.Builder(this)
            .setTitle("Emergency Sound Detected")
            .setMessage("Do you need help?")
            .setPositiveButton("Yes, send alert") { _, _ ->
                userResponded = true
                if (checkLocationPermissions()) {
                    try {
                        sendEmergencySMS()
                    } catch (e: SecurityException) {
                        Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("No, false alarm") { _, _ ->
                userResponded = true
                Toast.makeText(this, "Alert canceled", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    @RequiresPermission(
        allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
    )
    private fun sendEmergencySMS() {
        val prefs = getSharedPreferences("emergency_contacts", MODE_PRIVATE)
        val emergencyContacts = listOfNotNull(
            prefs.getString("contact1", null),
            prefs.getString("contact2", null),
            prefs.getString("contact3", null)
        ).filter { it.isNotBlank() }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val message = """
                    ⚠ URGENT: Emergency sound detected!
                    📍 Location: https://maps.google.com/?q=${location.latitude},${location.longitude}
                    ⏰ Time: ${SimpleDateFormat("dd-MM-yyyy | HH:mm", Locale.getDefault()).format(Date())}
                    — Emergency Audio Detection App
                """.trimIndent()

                for (number in emergencyContacts) {
                    smsManager.sendTextMessage(number, null, message, null, null)
                }

                Toast.makeText(this, "Emergency SMS sent", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location", Toast.LENGTH_SHORT).show()
        }
    }
}
