package com.zhy8416503.yolocam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

data class Detection(
    val x: Float, val y: Float,       // 归一化左上 (0~1), 以扶正后图像为基准
    val width: Float, val height: Float,
    val label: String,
    val confidence: Float
)

enum class ModelVariant(val assetName: String, val displayName: String) {
    N("yolo26n.tflite", "常规精度"),
    X("yolo26x.tflite", "超高精度")
}

class Detector(context: Context, variant: ModelVariant) {
    private val interpreter: Interpreter
    val labels: List<String>
    val variant: ModelVariant = variant
    // 扶正后图像宽高比 (Wo/Ho)
    var orientedAspect: Float = 9f / 16f
        private set

    companion object {
        const val INPUT_SIZE = 640
        const val CONF_THRESHOLD = 0.25f
        const val MAX_DETECTIONS = 300  // YOLO26 端到端输出 300 个
    }

    init {
        val options = Interpreter.Options().apply {
            // 骁龙8 Gen5 Adreno 840: GPU delegate 最快
            // 注意: X 模型较大, GPU 可能内存不足, 自动回退 CPU
            try {
                addDelegate(GpuDelegate())
            } catch (e: Exception) {
                // 回退 CPU
            }
            setNumThreads(4)
        }
        interpreter = Interpreter(loadModel(context, variant.assetName), options)
        labels = context.assets.open("coco_labels.txt")
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadModel(context: Context, assetName: String): ByteBuffer {
        val fd = context.assets.openFd(assetName)
        val input = FileInputStream(fd.fileDescriptor)
        val channel = input.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            .order(ByteOrder.nativeOrder())
    }

    /**
     * YOLO26 端到端: 输出 (1, 300, 6) = [x1,y1,x2,y2,conf,class_id], 无需 NMS。
     * 坐标为 0~640 像素。
     */
    fun detect(input: Bitmap): List<Detection> {
        // 1. 扶正: 后置相机 sensor 横屏, 顺时针90° -> 竖屏
        val upright = rotate90CW(input)
        val wo = upright.width.toFloat()
        val ho = upright.height.toFloat()
        if (wo <= 0 || ho <= 0) { upright.recycle(); return emptyList() }
        orientedAspect = wo / ho

        // 2. letterbox 到 640x640 (灰边 114)
        val lb = letterbox(upright)
        upright.recycle()

        // 3. Bitmap -> ByteBuffer (RGB, 0~1 float)
        val inputBuffer = bitmapToBuffer(lb)
        lb.recycle()

        // 4. 推理: 输出 [1,300,6]
        val output = Array(1) { Array(MAX_DETECTIONS) { FloatArray(6) } }
        interpreter.run(inputBuffer, output)

        // 5. 解码 (无需 NMS, 直接过滤)
        return decode(output[0], wo, ho)
    }

    private fun rotate90CW(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val dst = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(w * h)
        val dstPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                dstPixels[x * h + (h - 1 - y)] = srcPixels[y * w + x]
            }
        }
        dst.setPixels(dstPixels, 0, h, 0, 0, h, w)
        return dst
    }

    private fun letterbox(src: Bitmap): Bitmap {
        val s = min(INPUT_SIZE / src.width.toFloat(), INPUT_SIZE / src.height.toFloat())
        val nw = (src.width * s).toInt()
        val nh = (src.height * s).toInt()
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val dst = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        dst.eraseColor(Color.rgb(114, 114, 114))
        val dx = (INPUT_SIZE - nw) / 2
        val dy = (INPUT_SIZE - nh) / 2
        val canvas = android.graphics.Canvas(dst)
        canvas.drawBitmap(scaled, dx.toFloat(), dy.toFloat(), null)
        scaled.recycle()
        return dst
    }

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun decode(out: Array<FloatArray>, wo: Float, ho: Float): List<Detection> {
        data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                      val label: String, val conf: Float)
        val boxes = ArrayList<Box>()
        for (i in 0 until MAX_DETECTIONS) {
            val conf = out[i][4]
            if (conf < CONF_THRESHOLD) continue
            val clsId = out[i][5].toInt()
            if (clsId < 0 || clsId >= 80) continue
            val label = if (clsId < labels.size) labels[clsId] else "obj"
            boxes.add(Box(out[i][0], out[i][1], out[i][2], out[i][3], label, conf))
            if (boxes.size >= 50) break
        }

        // 640 模型坐标 -> 扶正后图像归一化坐标 (letterbox 逆变换)
        val lb = min(INPUT_SIZE / wo, INPUT_SIZE / ho)
        val offX = (INPUT_SIZE - wo * lb) / 2
        val offY = (INPUT_SIZE - ho * lb) / 2
        return boxes.map { b ->
            val x1n = ((b.x1 - offX) / lb) / wo
            val y1n = ((b.y1 - offY) / lb) / ho
            val x2n = ((b.x2 - offX) / lb) / wo
            val y2n = ((b.y2 - offY) / lb) / ho
            Detection(
                x = x1n.coerceIn(0f, 1f),
                y = y1n.coerceIn(0f, 1f),
                width = (x2n - x1n).coerceIn(0f, 1f),
                height = (y2n - y1n).coerceIn(0f, 1f),
                label = b.label,
                confidence = b.conf
            )
        }
    }

    fun close() { interpreter.close() }
}
