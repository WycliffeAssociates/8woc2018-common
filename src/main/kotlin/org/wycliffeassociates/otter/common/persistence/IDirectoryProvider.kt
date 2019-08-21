package org.wycliffeassociates.otter.common.persistence

import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.data.model.ResourceMetadata
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

interface IDirectoryProvider {

    // Create a directory to store the user's application projects/documents
    fun getUserDataDirectory(appendedPath: String = ""): File

    // Create a directory to store the application's private org.wycliffeassociates.otter.common.data
    fun getAppDataDirectory(appendedPath: String = ""): File

    // Create the directory for project audio
    fun getProjectAudioDirectory(sourceMetadata: ResourceMetadata, book: Collection): File

    fun getSourceContainerDirectory(container: ResourceContainer): File
    fun getDerivedContainerDirectory(metadata: ResourceMetadata, source: ResourceMetadata): File

    val resourceContainerDirectory: File
    val userProfileImageDirectory: File
    val userProfileAudioDirectory: File
    val audioPluginDirectory: File
}