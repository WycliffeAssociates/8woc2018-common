package org.wycliffeassociates.otter.common.domain.plugins

import io.reactivex.Completable
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import java.io.File

class ImportAudioPlugins(
    private val pluginRegistrar: IAudioPluginRegistrar,
    private val directoryProvider: IDirectoryProvider
) {
    fun importAll(): Completable {
        // Imports all the plugins from the app's plugin directory
        return pluginRegistrar.importAll(directoryProvider.audioPluginDirectory)
    }

    fun importExternal(externalPluginFile: File): Completable {
        // Import the external file and copy it into the app's plugin directory
        val newFile = directoryProvider.audioPluginDirectory.resolve(externalPluginFile.name)
        externalPluginFile.copyTo(newFile, true)
        return pluginRegistrar.import(newFile)
    }
}