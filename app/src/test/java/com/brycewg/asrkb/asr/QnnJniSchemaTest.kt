package com.brycewg.asrkb.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.QnnConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class QnnJniSchemaTest {
    @Test
    fun `pinned QNN JNI can read Moonshine fields even for SenseVoice`() {
        val moonshine = OfflineModelConfig().moonshine
        val decoder = moonshine.javaClass.getDeclaredField("decoder").apply { isAccessible = true }
        val qnn = moonshine.javaClass.getDeclaredField("qnnConfig").apply { isAccessible = true }
        assertEquals("", decoder.get(moonshine))
        assertEquals(QnnConfig::class.java, qnn.get(moonshine).javaClass)
        assertEquals(FloatArray::class.java, OfflineRecognizerResult::class.java.getDeclaredField("durations").type)
    }
}
