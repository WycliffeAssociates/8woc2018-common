package org.wycliffeassociates.otter.common.domain.resourcecontainer.project.markdown

import org.wycliffeassociates.otter.common.collections.tree.OtterTree
import org.wycliffeassociates.otter.common.collections.tree.Tree
import org.wycliffeassociates.otter.common.collections.tree.TreeNode
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.data.model.Content
import org.wycliffeassociates.otter.common.domain.resourcecontainer.ImportResult
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.IProjectReader
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.OtterFile
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Project
import java.io.BufferedReader
import org.wycliffeassociates.otter.common.collections.tree.OtterTreeNode
import org.wycliffeassociates.otter.common.domain.resourcecontainer.project.OtterFile.Companion.otterFileF
import java.io.File
import java.util.ArrayDeque
import java.util.zip.ZipFile

private const val FORMAT = "text/markdown"
private val extensions = Regex(".+\\.(md|mkdn?|mdown|markdown)$", RegexOption.IGNORE_CASE)

class MarkdownProjectReader() : IProjectReader {
    override fun constructProjectTree(
            container: ResourceContainer,
            project: Project,
            zipEntryTreeBuilder: IZipEntryTreeBuilder
    ): Pair<ImportResult, Tree> {

        val projectRoot: OtterFile
        val projectTreeRoot: OtterTree<OtterFile>

        // TODO: 2/25/19
        when (container.file.endsWith("zip")) {
            false -> {
                projectRoot = otterFileF(container.file.resolve(project.path))
                projectTreeRoot = container.file.resolve(project.path).buildFileTree()
            }
            true -> {
                projectRoot = otterFileF(container.file.toPath().resolve(project.path).toFile())
                projectTreeRoot = ZipFile(container.file).use { zip ->
                    zipEntryTreeBuilder.buildOtterFileTree(zip, project.path) }
            }
        }

        val collectionKey = container.manifest.dublinCore.identifier

        return projectTreeRoot
                .filterMarkdownFiles()
                ?.map<Any> { f -> contentList(f) ?: collection(collectionKey, f, projectRoot) }
                ?.flattenContent()
                ?.let { Pair(ImportResult.SUCCESS, it) }
                ?: Pair(ImportResult.LOAD_RC_ERROR, Tree(Unit))
    }

    private fun fileToId(f: OtterFile): Int =
            f.nameWithoutExtension.toIntOrNull() ?: 0

    private fun fileToSlug(f: OtterFile, root: OtterFile): String =
            root.parentFile?.let { parentFile ->
                f.toRelativeString(parentFile)
                        .split('/', '\\')
                        .map { it.toIntOrNull()?.toString() ?: it }
                        .joinToString("_")
            } ?: throw Exception("fileToSlug() call should not be made with null parentFile") // TODO. Also we could move this exception somewhere else if we set parentFile to a val

    private fun bufferedReaderProvider(f: OtterFile): (() -> BufferedReader)? =
            if (f.isFile) {
                { f.bufferedReader() }
            } else null

    private fun collection(key: String, f: OtterFile, projectRoot: OtterFile): Collection =
            collection(key, fileToSlug(f, projectRoot), fileToId(f))

    private fun collection(key: String, slug: String, id: Int): Collection =
            Collection(
                    sort = id,
                    slug = slug,
                    labelKey = key,
                    titleKey = "$id",
                    resourceContainer = null)

    private fun content(sort: Int, label: String, id: Int, text: String): Content? =
            if (text.isEmpty()) null
            else Content(sort, label, id, id, null, text, FORMAT)

    private fun contentList(f: OtterFile): List<Content>? =
            bufferedReaderProvider(f)
                    ?.let { contentList(it, fileToId(f)) }

    private fun contentList(brp: () -> BufferedReader, fileId: Int): List<Content> {
        val helpResources = brp().use { ParseMd.parse(it) }
        var sort = 1
        return helpResources.flatMap { helpResource ->
            listOfNotNull(
                    content(sort++, "title", fileId, helpResource.title),
                    content(sort++, "body", fileId, helpResource.body)
            )
        }
    }
}

internal fun File.buildFileTree(): OtterTree<OtterFile> {
    var treeRoot: OtterTree<OtterFile>? = null
    val treeCursor = ArrayDeque<OtterTree<OtterFile>>()
    this.walkTopDown()
            .onEnter { newDir ->
                OtterTree(otterFileF(newDir)).let { newDirNode ->
                    treeCursor.peek()?.addChild(newDirNode)
                    treeCursor.push(newDirNode)
                    true
                }
            }
            .onLeave { treeRoot = treeCursor.pop() }
            .filter { it.isFile }
            .map { OtterTreeNode(otterFileF(it)) }
            .forEach { treeCursor.peek()?.addChild(it) }
    return treeRoot ?: OtterTree(otterFileF(this))
}

private fun OtterTree<OtterFile>.filterMarkdownFiles(): OtterTree<OtterFile>? =
        this.filterPreserveParents { it.isFile && extensions.matches(it.name) }

private fun Tree.flattenContent(): Tree =
        Tree(this.value).also {
            it.addAll(
                    if (this.children.all { c -> c.value is List<*> }) {
                        this.children
                                .flatMap { c -> c.value as List<*> }
                                .filterNotNull()
                                .map { TreeNode(it) }
                    } else {
                        this.children
                                .map { if (it is Tree) it.flattenContent() else it }
                    }
            )
        }
