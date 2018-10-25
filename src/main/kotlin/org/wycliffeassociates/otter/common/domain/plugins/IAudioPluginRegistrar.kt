package org.wycliffeassociates.otter.common.domain.plugins

import io.reactivex.Completable
import java.io.File

interface IAudioPluginRegistrar {
    fun import(pluginFile: File): Completable
    fun importAll(pluginDir: File): Completable
}
