package com.zhy8416503.yolocam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * YOLOCam Android: YOLO26 双模型 (N=常规精度, X=超高精度)，顶部切换。
 * 一加 Ace 6T 定制: 默认 4K 预览, 推理目标 60fps。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var fpsText: TextView
    private lateinit var countText: TextView
    private lateinit var btnNormal: Button
    private lateinit var btnUltra: Button

    private var detector: Detector? = null
    private var currentVariant = ModelVariant.N
    private lateinit var cameraExecutor: ExecutorService

    // 推理节流: N 目标 60fps, X 目标 10fps (X 太慢)
    private var lastInferenceTime = 0L
    private var detecting = false

    // FPS 统计
    private var statCount = 0
    private var lastStatTime = System.nanoTime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        fpsText = findViewById(R.id.fpsText)
        countText = findViewById(R.id.countText)
        btnNormal = findViewById(R.id.btnNormal)
        btnUltra = findViewById(R.id.btnUltra)

        btnNormal.setOnClickListener { switchModel(ModelVariant.N) }
        btnUltra.setOnClickListener { switchModel(ModelVariant.X) }
        updateButtons()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun updateButtons() {
        btnNormal.isEnabled = currentVariant != ModelVariant.N
        btnUltra.isEnabled = currentVariant != ModelVariant.X
        btnNormal.alpha = if (currentVariant == ModelVariant.N) 1.0f else 0.5f
        btnUltra.alpha = if (currentVariant == ModelVariant.X) 1.0f else 0.5f
    }

    private fun switchModel(variant: ModelVariant) {
        if (variant == currentVariant) return
        currentVariant = variant
        updateButtons()
        // 在后台线程重建 Detector
        cameraExecutor.execute {
            try {
                detector?.close()
                detector = Detector(this, variant)
                runOnUiThread { fpsText.text = "已切换: ${variant.displayName}" }
            } catch (e: Exception) {
                runOnUiThread { fpsText.text = "模型加载失败" }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            fpsText.text = "需要相机权限"
        }
    }

    private fun startCamera() {
        try {
            detector = Detector(this, currentVariant)
        } catch (e: Exception) {
            fpsText.text = "模型加载失败: ${e.message}"
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            // 预览: 4K (一加 Ace 6T IMX906 支持)
            val preview = Preview.Builder()
                .setTargetResolution(Size(3840, 2160))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // 分析: 720p, 60fps 目标
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                fpsText.text = "相机启动失败"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        // 节流: N=60fps (16.6ms), X=10fps (100ms)
        val interval = if (currentVariant == ModelVariant.N) 16_666_666L else 100_000_000L
        val now = System.nanoTime()
        if (detecting || now - lastInferenceTime < interval) {
            image.close()
            return
        }
        val det = detector
        if (det == null) { image.close(); return }
        detecting = true
        lastInferenceTime = now

        try {
            val bmp = image.toBitmap()
            image.close()
            if (bmp == null) { detecting = false; return }

            val dets = det.detect(bmp)
            bmp.recycle()

            statCount++
            val dt = (now - lastStatTime) / 1e9
            var fps: Double? = null
            if (dt >= 1.0) {
                fps = statCount / dt
                statCount = 0
                lastStatTime = now
            }

            val aspect = det.orientedAspect
            runOnUiThread {
                overlay.detections = dets
                overlay.orientedAspect = aspect
                countText.text = "${dets.size} 个目标"
                if (fps != null) fpsText.text = "${fps.toInt()} fps"
            }
        } catch (e: Exception) {
            // 忽略单帧错误
        } finally {
            detecting = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
    }
}
