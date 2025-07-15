package com.example.emergencyaudiodetection

import android.Manifest
import android.telephony.SmsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.concurrent.thread
import com.example.emergencyaudiodetection.contact.ManageContactsActivity


class MainActivity : AppCompatActivity() {

    private lateinit var recordToggleButton: Button

    private lateinit var manageContactsButton: Button

    private var dispatcher: AudioDispatcher? = null
    private var isRecording = false
    private lateinit var tflite: Interpreter
    private lateinit var yamnet: Interpreter


    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordToggleButton = findViewById(R.id.recordButton)
        manageContactsButton = findViewById(R.id.manageContactsButton)

        recordToggleButton.setOnClickListener {
            if (!isRecording) {
                startRecording()
                recordToggleButton.text = getString(R.string.stop_listening)

            } else {
                stopRecording()
                recordToggleButton.text = getString(R.string.start_listening)
            }
        }


        manageContactsButton.setOnClickListener {
            val intent = Intent(this, ManageContactsActivity::class.java)

            startActivity(intent)
        }

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                PERMISSION_REQUEST_CODE
            )
        }

        try {
            tflite = Interpreter(loadModelFile("emergency_audio_model.tflite"))     // Classifier
            yamnet = Interpreter(loadModelFile("YamNet.tflite"))                // Feature extractor
            /*val outputTensorShape = yamnet.getOutputTensor(1).shape()
            Log.d("YAMNetDebug", "Output Tensor Shape: ${outputTensorShape.joinToString()}")*/

        } catch (e: IOException) {
            Toast.makeText(this, "Model failed to load", Toast.LENGTH_LONG).show()
            Log.e("ModelLoad", "Error loading model", e)
        }
    }

    private fun hasAllPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!hasAllPermissions()) {
                Toast.makeText(this, "Permissions denied!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun startRecording() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "Permissions are required to start recording.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("Recording", "Starting recording")

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, 15360, 7680)

        dispatcher?.addAudioProcessor(object : AudioProcessor {
            override fun processingFinished() {}

            override fun process(audioEvent: AudioEvent): Boolean {
                try {
                    val audioBuffer = audioEvent.floatBuffer
                    val inputLength = 15600

                    val yamnetInput = Array(1) { FloatArray(inputLength) }
                    for (i in 0 until inputLength) {
                        yamnetInput[0][i] = if (i < audioBuffer.size) audioBuffer[i] else 0f
                    }

                    // Use output index 1 to get embeddings from YamNet
                    val yamnetOutputMap = mutableMapOf<Int, Any>()
                    val embeddings = Array(96) { FloatArray(1024) } // shape: [96, 1024]
                    yamnetOutputMap[1] = embeddings

                    yamnet.runForMultipleInputsOutputs(yamnetInput, yamnetOutputMap)

                    // Mean-pool embeddings to get [1, 1024]
                    val pooledEmbedding = FloatArray(1024)
                    for (i in 0 until 96) {
                        for (j in 0 until 1024) {
                            pooledEmbedding[j] += embeddings[i][j]
                        }
                    }
                    for (j in 0 until 1024) {
                        pooledEmbedding[j] /= 96f
                    }

                    // Feed to classifier
                    val classifierInput = arrayOf(pooledEmbedding)
                    val classifierOutput = Array(1) { FloatArray(1) }

                    tflite.run(classifierInput, classifierOutput)

                    val confidence = classifierOutput[0][0]
                    Log.d("InferenceOutput", "Confidence: $confidence")

                    if (confidence > 0.5) {
                        runOnUiThread {
                            Toast.makeText(applicationContext, "🚨 Emergency Sound Detected!", Toast.LENGTH_SHORT).show()
                        }
                        triggerAlert()
                    }

                } catch (e: Exception) {
                    Log.e("YAMNetError", "Error in inference", e)
                }

                return true
            }



        })

        isRecording = true
        thread { dispatcher?.run() }

        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }


    private fun stopRecording() {
        isRecording = false
        dispatcher?.stop()
        dispatcher = null
        recordToggleButton.text = getString(R.string.start_listening)

        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }


    private fun triggerAlert() {
        runOnUiThread {
            val alertDialog = android.app.AlertDialog.Builder(this)
                .setTitle("🚨 Emergency Detected")
                .setMessage("Emergency detected! Do you want to send alert?")
                .setCancelable(false)
                .setPositiveButton("Yes") { _, _ ->
                    sendAlert()
                }
                .setNegativeButton("No") { _, _ ->
                    Toast.makeText(this, "Alert cancelled.", Toast.LENGTH_SHORT).show()
                }
                .create()

            alertDialog.show()

            // Auto-send alert if user doesn't respond in 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (alertDialog.isShowing) {
                    alertDialog.dismiss()
                    sendAlert()
                }
            }, 10000) // 10 seconds
        }
    }

    private fun sendAlert() {
        val sharedPreferences = getSharedPreferences("EmergencyContacts", MODE_PRIVATE)
        val contactSet = sharedPreferences.getStringSet("contacts", setOf()) ?: setOf()

        if (contactSet.isEmpty()) {
            Toast.makeText(this, "No emergency contacts found!", Toast.LENGTH_SHORT).show()
            return
        }

        var locationText = "Location not available"

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    locationText = "https://maps.google.com/?q=${it.latitude},${it.longitude}"
                }
                sendSmsToContacts(contactSet, locationText)
            }.addOnFailureListener {
                sendSmsToContacts(contactSet, locationText)
            }
        } else {
            sendSmsToContacts(contactSet, locationText)
        }
    }

    private fun sendSmsToContacts(contacts: Set<String>, location: String) {
        val smsManager = getSystemService(SmsManager::class.java)

        for (contact in contacts) {
            val message = "🚨 Emergency Detected!\nUser may be in danger.\nLocation: $location"
            smsManager.sendTextMessage(contact, null, message, null, null)
        }
        Toast.makeText(this, "🚨 Alert sent to all emergency contacts!", Toast.LENGTH_LONG).show()
    }


}
