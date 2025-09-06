package com.example.recoverx

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    private lateinit var cbImages: CheckBox
    private lateinit var cbVideos: CheckBox
    private var pickedTree: Uri? = null
    private val foundMedia = mutableListOf<DocumentFile>()
    private val suspectCorrupt = mutableListOf<DocumentFile>()

    private val pickTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pickedTree = uri
            log("เลือกโฟลเดอร์: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tv = findViewById(R.id.tvLog)
        cbImages = findViewById(R.id.cbImages)
        cbVideos = findViewById(R.id.cbVideos)

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickTreeLauncher.launch(null) }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.btnRepair).setOnClickListener { repairAll() }
    }

    private fun log(msg: String) { tv.append(msg + "\n") }

    private fun scan() {
        val tree = pickedTree ?: run { log("ยังไม่ได้เลือกโฟลเดอร์"); return }
        foundMedia.clear()
        suspectCorrupt.clear()

        val root = DocumentFile.fromTreeUri(this, tree) ?: run { log("เปิดโฟลเดอร์ไม่สำเร็จ"); return }
        val allowImage = cbImages.isChecked
        val allowVideo = cbVideos.isChecked

        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { f ->
                if (f.isDirectory) {
                    walk(f)
                } else {
                    val mime = f.type ?: ""
                    val isImg = mime.startsWith("image/")
                    val isVid = mime.startsWith("video/")
                    if ((allowImage && isImg) || (allowVideo && isVid)) {
                        foundMedia.add(f)
                        if (isProbablyCorrupt(f, isImg, isVid)) {
                            suspectCorrupt.add(f)
                        }
                    }
                }
            }
        }

        walk(root)
        log("สแกนเสร็จ: พบสื่อ ${foundMedia.size} ไฟล์; คาดว่าเสีย ${suspectCorrupt.size} ไฟล์")
    }

    private fun isProbablyCorrupt(f: DocumentFile, isImg: Boolean, isVid: Boolean): Boolean {
        return try {
            contentResolver.openInputStream(f.uri)?.use { input ->
                if (isImg) {
                    val bmp = BitmapFactory.decodeStream(input)
                    bmp == null
                } else if (isVid) {
                    val ex = MediaExtractor()
                    ex.setDataSource(this, f.uri, null)
                    val tracks = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                    ex.release()
                    tracks.isEmpty()
                } else false
            } ?: true
        } catch (t: Throwable) {
            true
        }
    }

    private fun copyAll() {
        if (foundMedia.isEmpty()) { log("ยังไม่มีรายการไฟล์ ให้สแกนก่อน"); return }
        val outDir = File(getExternalFilesDir(null), "recovered")
        outDir.mkdirs()
        var ok = 0; var fail = 0
        for (f in foundMedia) {
            val name = f.name ?: "unnamed"
            val dst = File(outDir, name)
            try {
                contentResolver.openInputStream(f.uri)?.use { input ->
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
                ok++
                log("คัดลอก: ${dst.absolutePath}")
            } catch (t: Throwable) {
                fail++
                log("คัดลอกล้มเหลว: $name → ${t.message}")
            }
        }
        log("สรุปคัดลอกเสร็จ: สำเร็จ $ok ล้มเหลว $fail (ไฟล์ถูกเก็บไว้ใน: ${outDir.absolutePath})")
    }

    private fun repairAll() {
        if (suspectCorrupt.isEmpty()) { log("ไม่พบไฟล์ที่คาดว่าเสีย ให้สแกนก่อนหรือไฟล์ปกติ"); return }
        val outDir = File(getExternalFilesDir(null), "repaired")
        outDir.mkdirs()
        var repaired = 0; var failed = 0
        for (f in suspectCorrupt) {
            val mime = f.type ?: ""
            val name = f.name ?: "unnamed"
            try {
                if (mime.startsWith("image/")) {
                    contentResolver.openInputStream(f.uri)?.use { input ->
                        val bmp = BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            val outFile = File(outDir, name.substringBeforeLast('.') + "_fixed.jpg")
                            FileOutputStream(outFile).use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            repaired++
                            log("ซ่อมภาพแล้ว: ${outFile.absolutePath}")
                        } else {
                            failed++; log("ซ่อมภาพล้มเหลว (decode ไม่ได้): $name")
                        }
                    }
                } else if (mime.startsWith("video/")) {
                    val tmpOut = File(outDir, name.substringBeforeLast('.') + "_fixed.mp4")
                    if (remuxVideo(contentResolver, f.uri, tmpOut)) {
                        repaired++; log("ซ่อมวิดีโอแล้ว: ${tmpOut.absolutePath}")
                    } else {
                        failed++; log("ซ่อมวิดีโอล้มเหลว: $name")
                    }
                } else {
                    failed++; log("ข้ามชนิดไฟล์: $name ($mime)")
                }
            } catch (t: Throwable) {
                failed++; log("ซ่อมล้มเหลว: $name → ${t.message}")
            }
        }
        log("สรุปซ่อมเสร็จ: สำเร็จ $repaired ล้มเหลว $failed (ไฟล์ถูกเก็บไว้ใน: ${outDir.absolutePath})")
    }

    private fun remuxVideo(resolver: ContentResolver, inUri: Uri, outFile: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(this, inUri, null)
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableListOf<Pair<Int,Int>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val newIndex = muxer.addTrack(format)
                    trackMap.add(i to newIndex)
                }
            }
            if (trackMap.isEmpty()) {
                extractor.release(); muxer.release(); return false
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            for ((srcIndex, dstIndex) in trackMap) {
                extractor.selectTrack(srcIndex)
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) { extractor.unselectTrack(srcIndex); break }
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstIndex, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop(); muxer.release(); extractor.release()
            true
        } catch (t: Throwable) {
            false
        }
    }
}
