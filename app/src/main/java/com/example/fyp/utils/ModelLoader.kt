package com.example.fyp.utils

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object ModelLoader {

    /**
     * Load a TFLite Interpreter from assets by model filename.
     * Caller must handle exceptions.
     */
    fun loadModelFromAssets(context: Context, modelName: String): Interpreter {
        val afd = context.assets.openFd(modelName)
        val stream = FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        return Interpreter(map)
    }
}
