package org.wycliffeassociates.otter.common.device

import io.reactivex.Observable

interface IAudioRecorder {
    fun start()
    fun stop()
    fun getAudioStream(): Observable<ByteArray>
}