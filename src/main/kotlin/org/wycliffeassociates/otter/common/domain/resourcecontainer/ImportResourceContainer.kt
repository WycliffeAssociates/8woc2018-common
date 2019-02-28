package org.wycliffeassociates.otter.common.domain.resourcecontainer

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.wycliffeassociates.otter.common.collections.tree.Tree
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IProjectReader
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import org.wycliffeassociates.otter.common.persistence.repositories.IResourceContainerRepository
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class ImportResourceContainer(
        private val resourceContainerRepository: IResourceContainerRepository,
        private val directoryProvider: IDirectoryProvider,
        private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) {
    fun import(file: File): Single<ImportResult> {
        return when {
            file.isDirectory -> importContainerDirectory(file)
            file.extension == "zip" -> importContainerZipFile(file)
            else -> Single.just(ImportResult.INVALID_RC)
        }
    }

    private fun File.contains(name: String): Boolean {
        if (!this.isDirectory) {
            throw Exception("Cannot call contains on non-directory file")
        }
        return this.listFiles().map { it.name }.contains(name)
    }

    private fun importContainerZipFile(file: File): Single<ImportResult> {
        if (!ZipFile(file).use(this::validateResourceContainer)) return Single.just(ImportResult.INVALID_RC)

        val internalDir = getInternalDirectory(file)
                ?: return Single.just(ImportResult.LOAD_RC_ERROR)
        if (internalDir.exists() && internalDir.contains(file.name)) {
            // Collision on disk: Can't import the resource container
            // Assumes that filesystem internal app directory and database are in sync
            return Single.just(ImportResult.ALREADY_EXISTS)
        }

        // Copy to the internal directory
        val newZipFile = copyFileToInternalDirectory(file, internalDir)

        return importFromInternalDir(newZipFile, internalDir)
    }

    private fun importContainerDirectory(directory: File) =
            Single
                    .just(directory)
                    .flatMap { containerDir ->
                        // Is this a valid resource container
                        if (!validateResourceContainer(containerDir)) return@flatMap Single.just(ImportResult.INVALID_RC)

                        val internalDir = getInternalDirectory(containerDir) ?:
                            return@flatMap Single.just(ImportResult.LOAD_RC_ERROR)
                        if (internalDir.exists() && internalDir.listFiles().isNotEmpty()) {
                            // Collision on disk: Can't import the resource container
                            // Assumes that filesystem internal app directory and database are in sync
                            return@flatMap Single.just(ImportResult.ALREADY_EXISTS)
                        }

                        // Copy to the internal directory
                        val newDirectory = copyRecursivelyToInternalDirectory(containerDir, internalDir)

                        return@flatMap importFromInternalDir(newDirectory, newDirectory)
                    }
                    .subscribeOn(Schedulers.io())

    private fun getInternalDirectory(file: File): File? {

        // Load the external container to get the metadata we need to figure out where to copy to
        val extContainer = try {
            ResourceContainer.load(file, OtterResourceContainerConfig())
        } catch (e: Exception) {
            // Could be checked or unchecked exception from RC library
            e.printStackTrace()
            return null
        }
        return directoryProvider.getSourceContainerDirectory(extContainer)
    }

    private fun importFromInternalDir(fileToLoad: File, newDir: File): Single<ImportResult> {

        // Load the internal container
        val container = try {
            ResourceContainer.load(fileToLoad, OtterResourceContainerConfig())
        } catch (e: Exception) {
            return cleanUp(newDir, ImportResult.LOAD_RC_ERROR)
        }

        val (constructResult, tree) = constructContainerTree(container)
        if (constructResult != ImportResult.SUCCESS) return cleanUp(newDir, constructResult)

        return resourceContainerRepository
                .importResourceContainer(container, tree, container.manifest.dublinCore.language.identifier)
                .toSingle { ImportResult.SUCCESS }
                .doOnError { newDir.deleteRecursively() }
    }

    private fun cleanUp(containerDir: File, result: ImportResult): Single<ImportResult> = Single.fromCallable {
        containerDir.deleteRecursively()
        return@fromCallable result
    }

    private fun validateResourceContainer(dir: File): Boolean = dir.contains("manifest.yaml")

    private fun validateResourceContainer(zip: ZipFile): Boolean = zip.getEntry("manifest.yaml") != null

    private fun copyFileToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container zip file into the correct directory
        val destinationFile = File(destinationDirectory, filepath.name)
        if (filepath.absoluteFile != destinationFile) {
            filepath.copyTo(destinationFile, true)
            val success = destinationDirectory.contains(filepath.name)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationFile
    }

    private fun copyRecursivelyToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container into the correct directory
        if (filepath.absoluteFile != destinationDirectory) {
            val success = filepath.copyRecursively(destinationDirectory, true)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationDirectory
    }

    private fun makeExpandedContainer(container: ResourceContainer): ImportResult {
        val dublinCore = container.manifest.dublinCore
        if (dublinCore.type == "bundle" && dublinCore.format.startsWith("text/usfm")) {
            return if (container.expandUSFMBundle()) ImportResult.SUCCESS else ImportResult.INVALID_CONTENT
        }
        return ImportResult.SUCCESS
    }

    private fun constructContainerTree(container: ResourceContainer): Pair<ImportResult, Tree> {
        val projectReader = IProjectReader.build(container.manifest.dublinCore.format)
                ?: return Pair(ImportResult.UNSUPPORTED_CONTENT, Tree(Unit))
        val root = Tree(container.toCollection())
        val categoryInfo = container.otterConfigCategories()
        for (project in container.manifest.projects) {
            var parent = root
            for (categorySlug in project.categories) {
                // use the `latest` RC spec to treat categories as hierarchical
                // look for a matching category under the parent
                val existingCategory = parent.children
                        .map { it as? Tree }
                        .filter { (it?.value as? Collection)?.slug == categorySlug }
                        .firstOrNull()
                parent = if (existingCategory != null) {
                    existingCategory
                } else {
                    // category node does not yet exist
                    val category = categoryInfo.filter { it.identifier == categorySlug }.firstOrNull() ?: continue
                    val categoryNode = Tree(category.toCollection())
                    parent.addChild(categoryNode)
                    categoryNode
                }
            }
            val projectResult = projectReader.constructProjectTree(container, project, zipEntryTreeBuilder)
            if (projectResult.first == ImportResult.SUCCESS) {
                parent.addChild(projectResult.second)
            } else {
                return Pair(projectResult.first, Tree(Unit))
            }
        }
        return Pair(ImportResult.SUCCESS, root)
    }
}