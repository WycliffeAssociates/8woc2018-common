package org.wycliffeassociates.otter.common.domain.resourcecontainer.project.usfm

import org.wycliffeassociates.otter.common.collections.tree.Tree
import org.wycliffeassociates.otter.common.collections.tree.TreeNode
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.data.model.Content
import org.wycliffeassociates.otter.common.domain.resourcecontainer.ImportResult
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IProjectReader
import org.wycliffeassociates.otter.common.domain.resourcecontainer.toCollection
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Project
import java.io.BufferedReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class UsfmProjectReader : IProjectReader {
    override fun constructProjectTree(
            container: ResourceContainer,
            project: Project,
            zipEntryTreeBuilder: IZipEntryTreeBuilder
    ): Pair<ImportResult, Tree> {
        // TODO 2/25/19
        return when (container.file.endsWith("zip")) {
            false -> constructTreeFromDirOrFile(container, project)
            true -> constructTreeFromZip(container, project)
            else -> Pair(ImportResult.LOAD_RC_ERROR, Tree(Unit))
        }
    }
    private fun constructTreeFromDirOrFile(container: ResourceContainer, project: Project): Pair<ImportResult, Tree> {
        var result: ImportResult = ImportResult.SUCCESS
        val projectTree = Tree(project.toCollection())

        val projectLocation = container.file.resolve(project.path)
        if (projectLocation.isDirectory) {
            val files = projectLocation.listFiles()
            for (file in files) {
                result = parseFileIntoProjectTree(file, projectTree, project.identifier)
                if (result != ImportResult.SUCCESS) return Pair(result, Tree(Unit))
            }
        } else {
            // Single file
            result = parseFileIntoProjectTree(projectLocation, projectTree, project.identifier)
            if (result != ImportResult.SUCCESS) return Pair(result, Tree(Unit))
        }
        return Pair(result, projectTree)
    }

    private fun constructTreeFromZip(container: ResourceContainer, project: Project): Pair<ImportResult, Tree> {
        // Find the appropriate zip entry and use it to construct the project tree
        val zip = ZipFile(container.file) // TODO: 2/25/19
        zip.entries().toList().filter {
            project.path.contains(it.name)
        }.firstOrNull()?.let {
            return when (it.name.contains(".usfm", ignoreCase = true)) {
                true -> {
                    val projectTree = Tree(project.toCollection())
                    val result = parseFromBufferedReader(
                            zip.getInputStream(it).bufferedReader(),
                            projectTree,
                            project.identifier
                    )
                    return when (result) {
                        ImportResult.SUCCESS -> Pair(result, projectTree)
                        else -> Pair(result, Tree(Unit))
                    }
                }
                false -> Pair(ImportResult.UNSUPPORTED_CONTENT, Tree(Unit))
            }
        } ?: return Pair(ImportResult.LOAD_RC_ERROR, Tree(Unit))
    }

    private fun parseFileIntoProjectTree(file: File, root: Tree, projectIdentifier: String): ImportResult {
        return when (file.extension) {
            "usfm", "USFM" -> {
                parseFromBufferedReader(file.bufferedReader(), root, projectIdentifier)
            }
            else -> {
                ImportResult.UNSUPPORTED_CONTENT
            }
        }
    }

    private fun parseFromBufferedReader(bufferedReader: BufferedReader, root: Tree, projectIdentifier: String): ImportResult {
        return try {
            val chapters = parseUSFMToChapterTrees(bufferedReader, projectIdentifier)
            root.addAll(chapters)
            ImportResult.SUCCESS
        } catch (e: RuntimeException) {
            ImportResult.INVALID_CONTENT
        }
    }

    private fun parseUSFMToChapterTrees(bufferedReader: BufferedReader, projectSlug: String): List<Tree> {
        val doc = ParseUsfm(bufferedReader).parse()
        return doc.chapters.map { chapter ->
            val chapterSlug = "${projectSlug}_${chapter.key}"
            val chapterCollection = Collection(
                    chapter.key,
                    chapterSlug,
                    "chapter",
                    chapter.key.toString(),
                    null
            )
            val chapterTree = Tree(chapterCollection)
            // create a chunk for the whole chapter
            val chapChunk = Content(
                    0,
                    "chapter",
                    chapter.value.values.first().number,
                    chapter.value.values.last().number,
                    null,
                    null,
                    null
            )
            chapterTree.addChild(TreeNode(chapChunk))

            // Create content for each verse
            for (verse in chapter.value.values) {
                val content = Content(
                        verse.number,
                        "verse",
                        verse.number,
                        verse.number,
                        null,
                        null,
                        null
                )
                chapterTree.addChild(TreeNode(content))
            }
            return@map chapterTree
        }
    }
}