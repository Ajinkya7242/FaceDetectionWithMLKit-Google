package com.example.facedetectionandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.facedetectionandroid.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private val CAMERA_PERMISSION_REQUEST_CODE = 1001
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var binding: ActivityMainBinding
    private lateinit var handler: Handler
    private lateinit var backgroundThread: HandlerThread
    private lateinit var imageReader: ImageReader
    private lateinit var faceDetector: FaceDetector
    private var isUsingFrontCamera = true
    private  var currentCameraId: String?=null
    lateinit var rotatedBitmap:Bitmap


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize Face Detector
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)  // Enable classification to get probabilities
            .build()


        faceDetector = FaceDetection.getClient(options)

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                setupCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        startBackgroundThread()
        requestPermissions()

        binding.imgSwitch.setOnClickListener {
            isUsingFrontCamera = !isUsingFrontCamera
            if (::cameraDevice.isInitialized) {
                cameraDevice.close()
            }
            setupCamera()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        handler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            setupCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        }
    }

    private fun setupCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        currentCameraId = if (isUsingFrontCamera) {
            getFrontFacingCameraId(cameraManager)
        } else {
            getBackFacingCameraId(cameraManager)
        }

        if (currentCameraId != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            cameraManager.openCamera(currentCameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCameraSession()
                    schedulePhotoCapture()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, handler)
        } else {
            Log.e("CameraSetup", "No camera ID found")
        }
    }

    private fun getBackFacingCameraId(manager: CameraManager): String? {
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId
            }
        }
        return null
    }



    private fun startCameraSession() {
        val texture = binding.textureView.surfaceTexture
        texture?.setDefaultBufferSize(640, 480)
        val surface = Surface(texture)

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                processImage(image)
                image.close()
            }
        }, handler)

        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(surface)

        cameraDevice.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                captureSession.setRepeatingRequest(requestBuilder.build(), null, handler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                // Handle configuration failure
            }
        }, handler)
    }



    private fun schedulePhotoCapture() {
        handler.postDelayed({
            capturePhoto()
            schedulePhotoCapture()
        }, 5000) // 30 seconds
    }

    private fun capturePhoto() {
        if (::captureSession.isInitialized) {
            val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(imageReader.surface)
            captureSession.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                }
            }, handler)
        }
    }

    private fun processImage(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Convert ByteArray to Bitmap
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotate the bitmap by 90 degrees (change this if needed)
        if(isUsingFrontCamera){
             rotatedBitmap = rotateBitmap(bitmap, 270f)
        }
        else{
            rotatedBitmap = rotateBitmap(bitmap, 90f)

        }

        // Convert Bitmap to InputImage
        val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)

        // Process the image for face detection
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                // Task completed successfully
                runOnUiThread {
                    binding.imgFace.setImageBitmap(rotatedBitmap)
                    handleFaces(faces)
                }
            }
            .addOnFailureListener { e ->
                // Task failed with an exception
                Log.e("FaceDetection", "Face detection failed", e)
            }
    }

    // Function to rotate bitmap by a specified angle
    private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    private fun handleFaces(faces: List<Face>) {
        faces.forEach { face ->
            Log.d("FaceDetection", "Smiling Probability: ${face.smilingProbability}")
            Log.d("FaceDetection", "Left Eye Open Probability: ${face.leftEyeOpenProbability}")
            Log.d("FaceDetection", "Right Eye Open Probability: ${face.rightEyeOpenProbability}")
        }

        val logEntries = faces.mapIndexed { index, face ->
            val emotion = detectEmotion(face)
            "Face${index + 1}: Emotion - $emotion"
        }


        val resultText = logEntries.joinToString("\n")

        val faceCount = faces.size
        val emotions = faces.map { detectEmotion(it) }

        Log.d("FaceDetection", resultText)

        runOnUiThread {
            binding.txtCnt.text = faces.size.toString()
            binding.textView.text = resultText
        }
    }


    private fun detectEmotion(face: Face): String {
        if (face.smilingProbability != null && face.smilingProbability!! >= 0) {
            if (face.smilingProbability!! > 0.5) {
                return "Happy  ${face.smilingProbability} %"
            }
        }

        if (face.leftEyeOpenProbability != null && face.rightEyeOpenProbability != null &&
            face.leftEyeOpenProbability!! >= 0 && face.rightEyeOpenProbability!! >= 0) {
            if (face.leftEyeOpenProbability!! < 0.5 && face.rightEyeOpenProbability!! < 0.5) {

                return "Sleepy  ${face.leftEyeOpenProbability} %"

            }
        }

        return "Neutral"
    }


    private fun getFrontFacingCameraId(manager: CameraManager): String? {
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId
            }
        }
        return null
    }



    override fun onDestroy() {
        super.onDestroy()
        cameraDevice.close()
        stopBackgroundThread()
    }
}
