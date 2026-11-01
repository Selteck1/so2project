package com.selteck.so2project.automation

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenTextAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    fun recognize(bitmap: Bitmap): Task<String> =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).continueWith { task ->
            if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Не удалось распознать текст")
            task.result?.text.orEmpty()
        }
    fun close() = recognizer.close()
}
