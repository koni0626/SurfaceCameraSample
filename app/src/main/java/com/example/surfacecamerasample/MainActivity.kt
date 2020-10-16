package com.example.surfacecamerasample

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.gms.vision.barcode.BarcodeDetector
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

private const val PERMISSIONS_REQUEST_CODE = 1234

private val PERMISSIONS_REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
)

class MainActivity : AppCompatActivity() {

    private lateinit var appExecutor: Executor
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var barcodeValues: MutableMap<String, Int> = mutableMapOf()

    private lateinit var detector: BarcodeDetector

    private val imageSavedCB =
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults:
                                      ImageCapture.OutputFileResults) {
                Toast.makeText(applicationContext,
                    "save to file.", Toast.LENGTH_LONG).show()
            }

            override fun onError(exception: ImageCaptureException) {}
        }

    private fun toBitmap(image: Image):Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        var nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        Log.d("konishi", "yuvImage.width:${image.width} yuvImage.height:${image.height}")
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        var mat:Matrix = Matrix()
        mat.postRotate(90.0f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
    }

    private val imageAnalizer = object : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val format = imageProxy.format
            val imageWidth = imageProxy.width
            val imageHeight = imageProxy.height

            //imageをビットマップに変換する
            var dispBitmap = imageProxy.image?.let { toBitmap(it) }

            //ここでbitmapに何か書き込みたい
            var paint = Paint()
            paint.setColor(Color.RED)
            paint.style = Paint.Style.STROKE

            //var dispBitmap = originalBitmap?.copy(Bitmap.Config.ARGB_8888, true)
            dispBitmap?.let {
                val r = barcodeDetect(it)
                    imageView.setImageBitmap(it)
            }

            Log.d("konishi", "format ${format} width: ${dispBitmap?.width} height:${dispBitmap?.height}")


            imageProxy.close()   //closeを呼び出すと次のフレームを取得する
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        if (!hasPermissions(applicationContext)) {
            ActivityCompat.requestPermissions(this,
                PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }

        setContentView(R.layout.activity_main)

        //バーコード読み取りのインスタンス作成
        //ここでチェックディジット追加できるか調べる
        detector = BarcodeDetector.Builder(applicationContext)
            .setBarcodeFormats(
                        Barcode.EAN_13 or
                        Barcode.EAN_8 or
                        Barcode.ITF or
                        Barcode.CODE_128 or
                        Barcode.CODE_93 or
                        Barcode.CODE_39 or
                        Barcode.CODABAR
            )
            .build()
        if (!detector.isOperational) {
            Log.d("konishi", "error init barcode detector")
        }


        appExecutor = ContextCompat.getMainExecutor(applicationContext)

        bindCameraUseCases()

        button1.setOnClickListener {
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, getFileName())

            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")

            val outputOption = ImageCapture.OutputFileOptions
                .Builder(getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues)
                .build()

            imageCapture?.takePicture(outputOption, appExecutor, imageSavedCB)
        }
    }

    private fun bindCameraUseCases() {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val cameraProvider = ProcessCameraProvider.getInstance(applicationContext)

        cameraProvider.addListener(Runnable {
            val cameraProvider =
                cameraProvider.get()

            val size:Size = Size(720, 1280)
            preview = Preview.Builder()
                //.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(size)
                .build()

            preview?.setSurfaceProvider(preview1.createSurfaceProvider(camera?.cameraInfo))

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                //.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetResolution(size)
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                //.setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(size)
                .build()

            imageAnalyzer?.setAnalyzer(appExecutor, imageAnalizer)


            cameraProvider.unbindAll()

            try {
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector,
                    preview, imageCapture, imageAnalyzer
                )
            } catch(exc: Exception) {}
        }, appExecutor)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray) {

        super.onRequestPermissionsResult(requestCode,
            permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (PackageManager.PERMISSION_GRANTED == grantResults.firstOrNull()) {
                Toast.makeText(applicationContext, "Permission granted!",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(applicationContext, "Permission denied...",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun barcodeDetect(bitmap: Bitmap): Bitmap? {


        val frame = Frame.Builder().setBitmap(bitmap).build()
        val barcodes = detector.detect(frame)

        var paint = Paint()
        paint.strokeWidth = 6.0f
        paint.style = Paint.Style.STROKE

        var fontPaint = Paint()
        fontPaint.strokeWidth = 1.0f
        fontPaint.textSize = 40.0f

        val c = Canvas(bitmap)
        for (r in barcodes.valueIterator()) {
            val rect = r.boundingBox
            val value = r.rawValue
            if(barcodeValues.contains(value)) {
                // 既に存在している
               var v = 0
                barcodeValues[value]?.let {
                    v = it
                }
                barcodeValues[value] = v + 1
            }
            else {
                paint.setColor(Color.argb(255, 255, 0, 0))
                fontPaint.setColor(Color.argb(255, 255, 0, 0))
                barcodeValues[value] = 1
            }
            var barcodeCount = barcodeValues[value]
            var color:Int = Color.argb(255, 0, 255, 0)
            barcodeCount?.let {
                if(it == 1) {
                    color = Color.argb(255, 255, 0, 0)
                }
                else if(it < 5) {
                    color = Color.argb(255, 255, 255, 0)
                }
            }

            paint.setColor(color)
            fontPaint.setColor(color)
            c.drawRect(rect, paint)
            c.drawText("${value}", rect.left.toFloat(), rect.top.toFloat(), fontPaint)
        }
        var count = barcodeValues.count()
        paint.setColor(Color.argb(255, 0, 255, 255))
        paint.strokeWidth = 1.0f
        paint.textSize = 20.0f
        paint.style = Paint.Style.FILL
        c.drawText("${count}", 10.0f, 100.0f, paint)
        Log.d("barcode", "========================")
        barcodeValues.forEach {
            Log.d("barcode", "barcode:${it.key} count:${it.value}")
        }

        return bitmap
    }

    companion object {
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val EXTENSION = ".jpg"

        fun getFileName(): String {
            return SimpleDateFormat(FILENAME, Locale.US)
                .format(System.currentTimeMillis()
                ) + EXTENSION
        }

        fun hasPermissions(context: Context) =
            PERMISSIONS_REQUIRED.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }
}