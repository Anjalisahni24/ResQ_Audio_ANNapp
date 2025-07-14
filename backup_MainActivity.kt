package com.example.emergencyaudiodetection

import android.Manifest
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
import be.tarsos.dsp.mfcc.MFCC
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

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MODEL_INPUT_SIZE = 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordToggleButton = findViewById(R.id.recordButton)

        recordToggleButton.setOnClickListener {
            if (!isRecording) {
                startRecording()
                recordToggleButton.text = "Stop Listening"
            } else {
                stopRecording()
                recordToggleButton.text = "Start Listening"
            }
        }

        manageContactsButton = findViewById(R.id.manageContactsButton)

        recordToggleButton.setOnClickListener {
            if (!isRecording) {
                startRecording()
                recordToggleButton.text = "Stop Listening"
            } else {
                stopRecording()
                recordToggleButton.text = "Start Listening"
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
            tflite = Interpreter(loadModelFile("emergency_audio_model.tflite"))
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

        /* startButton.text = "Stop Listening"  // 💡 Toggle Button text */

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, 1024, 512)
        val mfcc = MFCC(1024, SAMPLE_RATE.toFloat(), 13, 20, 300f, 3000f)

        dispatcher?.addAudioProcessor(object : AudioProcessor {
            override fun processingFinished() {}

            override fun process(audioEvent: AudioEvent): Boolean {
                mfcc.process(audioEvent) // ✅ This is required
                val mfccFeatures = mfcc.mfcc
                val inputFeature = FloatArray(MODEL_INPUT_SIZE)

                val copiedSize = mfccFeatures.size.coerceAtMost(MODEL_INPUT_SIZE)
                for (i in 0 until copiedSize) {
                    inputFeature[i] = mfccFeatures[i]
                }

                for (i in copiedSize until MODEL_INPUT_SIZE) {
                    inputFeature[i] = 0f
                }

                runInference(inputFeature)
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
        recordToggleButton.text = "Start Listening"

        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun runInference(inputFeature: FloatArray) {
        try {
            val input = arrayOf(inputFeature)
            val output = Array(1) { FloatArray(1) }

            tflite.run(input, output)

            val confidence = output[0][0]
            Log.d("InferenceOutput", "Confidence: $confidence")

            if (confidence > 0.5) {
                Log.d("Prediction", "Emergency Sound Detected!")
                // TODO: Trigger SMS/location here
            } else {
                Log.d("Prediction", "Normal sound.")
            }
        } catch (e: Exception) {
            Log.e("InferenceError", "Error during model inference", e)
        }
    }
}
