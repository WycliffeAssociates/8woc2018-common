package org.wycliffeassociates.otter.common.app

import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.wycliffeassociates.otter.common.collections.FloatRingBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ActiveRecordingRenderer(
    stream: Observable<ByteArray>
) {

    val floatBuffer = FloatRingBuffer(1845)

    val pcmCompressor = PCMCompressor(floatBuffer, 100)

    val bb = ByteBuffer.allocate(1024)

    init {
        bb.order(ByteOrder.LITTLE_ENDIAN)
    }

    val activeRenderer = stream
        .subscribeOn(Schedulers.io())
        .subscribe {
            bb.put(it)
            bb.position(0)
            while (bb.hasRemaining()) {
                val short = bb.short
                pcmCompressor.add(short.toFloat())
            }
            bb.clear()
        }
}