package org.wycliffeassociates.otter.common.domain

import io.reactivex.*
import org.wycliffeassociates.otter.common.collections.tree.Node
import org.wycliffeassociates.otter.common.collections.tree.Tree
import org.wycliffeassociates.otter.common.collections.tree.TreeNode
import org.wycliffeassociates.otter.common.data.model.Chunk
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.data.model.Language
import org.wycliffeassociates.otter.common.data.model.ResourceMetadata
import org.wycliffeassociates.otter.common.domain.mapper.mapToMetadata
import org.wycliffeassociates.otter.common.domain.usfm.ParseUsfm
import org.wycliffeassociates.otter.common.persistence.repositories.*

import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.DublinCore
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.entity.project
import org.wycliffeassociates.resourcecontainer.errors.RCException

import java.io.File
import java.io.FileFilter
import java.io.IOException
import javax.swing.text.AbstractDocument


class ImportResourceContainer(
        private val languageRepository: ILanguageRepository,
        private val metadataRepository: IResourceMetadataRepository,
        private val collectionRepository: ICollectionRepository,
        private val chunkRepository: IChunkRepository,
        directoryProvider: IDirectoryProvider
) {

    //TODO: Remove this when Bible, OT, NT are included as part of a resource container
    fun importBible(meta: ResourceMetadata) {
        //Initialize bible and testament collections
        val bible = Collection(1, "bible", "bible", "Bible", meta)
        val ot = Collection(1, "bible-ot", "testament", "Old Testament", meta)
        val nt = Collection(2, "bible-nt", "testament", "New Testament", meta)
        val bibleid = collectionRepository.insert(bible).blockingGet()
        bible.id = bibleid
        val otid = collectionRepository.insert(ot).blockingGet()
        ot.id = otid
        val ntid = collectionRepository.insert(nt).blockingGet()
        nt.id = ntid
        collectionRepository.updateParent(ot, bible).subscribe()
        collectionRepository.updateParent(nt, bible).subscribe()
    }

    private val rcDirectory = File(directoryProvider.getAppDataDirectory(), "rc")

    fun import(file: File): Completable {
        return when {
            file.isDirectory -> importDirectory(file)
            else -> Completable.complete()
        }
    }

    private fun importDirectory(dir: File): Completable {
        if (validateResourceContainer(dir)) {
            if (dir.parentFile?.absolutePath != rcDirectory.absolutePath) {
                val success = dir.copyRecursively(File(rcDirectory, dir.name), true)
                if (!success) {
                    throw IOException("Could not copy resource container ${dir.name} to resource container directory")
                }
            }
            return importResourceContainer(File(rcDirectory, dir.name))
        } else {
            return Completable.error(RCException("Missing manifest.yaml"))
        }
    }

    private fun validateResourceContainer(dir: File): Boolean {
        val names = dir.listFiles().map { it.name }
        return names.contains("manifest.yaml")
    }

    private fun importResourceContainer(container: File): Completable {
        val rc = ResourceContainer.load(container)
        val dc = rc.manifest.dublinCore

        if (dc.type == "bundle" && dc.format == "text/usfm") {
            expandResourceContainerBundle(rc)
        }

        val tree = constructContainerTree(rc)
        return collectionRepository.importResourceContainer(tree, dc.language.identifier)
    }

    private fun constructContainerTree(rc: ResourceContainer): Tree {
        val root = constructRoot(rc)
        val categories = getCategories(rc)
        root.addAll(categories.map { Tree(it) })
        for (category in root.children) {
            val projects = getProjectsInCategory(rc.manifest, (category.value as Collection).slug)
            val projectTrees = constructContentTrees(projects, rc)
            (category as Tree).addAll(projectTrees)
        }
        val projects = getProjectsWithoutCategory(rc.manifest)
        val projectTrees = constructContentTrees(projects, rc)
        root.addAll(projectTrees)
        return root
    }

    private fun constructContentTrees(projects: List<Project>, rc: ResourceContainer): List<Tree> {
        val projectTrees = projects.map { Tree(it.mapToCollection(rc.type())) }
        for ((idx, project) in projects.withIndex()) {
            val content = getChunksInProject(project)
            projectTrees[idx].addAll(content.map { TreeNode(it) })
        }
        return projectTrees
    }

    private fun constructRoot(rc: ResourceContainer): Tree {
        val dc = rc.manifest.dublinCore
        val slug = dc.identifier
        val title = dc.title
        val label = dc.type
        val collection = Collection(0, slug, label, title, null)
        return Tree(collection)
    }

    private fun getChunksInProject(project: Project): List<Chunk> {
        TODO()
    }

    private fun getCategories(rc: ResourceContainer): List<Collection> {
        TODO()
    }

    private fun getProjectsInCategory(manifest: Manifest, categorySlug: String): List<Project> {
        val projects = manifest.projects.filter { it.categories.contains(categorySlug) }
        return projects
    }

    private fun getProjectsWithoutCategory(manifest: Manifest): List<Project> {
        val projects = manifest.projects.filter { it.categories.isEmpty() }
        return projects
    }

    fun expandResourceContainerBundle(rc: ResourceContainer) {
        val dc = rc.manifest.dublinCore
        dc.type = "book"

        for (project in rc.manifest.projects) {
            expandUsfm(rc.dir, project)
        }

        rc.writeManifest()
    }

    fun expandUsfm(root: File, project: Project) {
        val projectRoot = File(root, project.identifier)
        projectRoot.mkdir()
        val usfmFile = File(root, project.path)
        if (usfmFile.exists() && usfmFile.extension == "usfm") {
            val book = ParseUsfm(usfmFile).parse()
            val chapterPadding = book.chapters.size.toString().length //length of the string version of the number of chapters
            val bookDir = File(root, project.identifier)
            bookDir.mkdir()
            for (chapter in book.chapters.entries) {
                val chapterFile = File(bookDir, chapter.key.toString().padStart(chapterPadding, '0') + ".usfm")
                val verses = chapter.value.entries.map { it.value }.toTypedArray()
                verses.sortBy { it.number }
                chapterFile.bufferedWriter().use {
                    it.write("\\c ${chapter.key}")
                    it.newLine()
                    for (verse in verses) {
                        it.appendln("\\v ${verse.number} ${verse.text}")
                    }
                }
            }
            usfmFile.delete()
        }
        project.path = "./${project.identifier}"
    }

    private fun importProject(p: Project, resourceMetadata: ResourceMetadata): Completable {
        return Observable.just(p.mapToCollection(resourceMetadata.type, resourceMetadata))
                .flatMap(
                        { book ->
                            collectionRepository.insert(book).toObservable()
                        },
                        { book: Collection, result: Int -> Pair(book, result) }
                )
                .map {
                    val book = it.first
                    book.id = it.second
                    return@map book
                }.flatMap(
                        { book: Collection ->
                            collectionRepository.getBySlugAndContainer(p.categories.first(), book.resourceContainer!!).toObservable()
                        },
                        { book: Collection, result: Collection -> Pair(book, result) }
                ).flatMapSingle { (book, parent): Pair<Collection, Collection> ->
                    val book = book
                    return@flatMapSingle collectionRepository.updateParent(book, parent).toSingle { Pair(book, parent) }
                }.flatMapCompletable { (book, res) ->
                    importChapters(p, book, resourceMetadata)
                }

    }

    private fun importChapters(project: Project, book: Collection, meta: ResourceMetadata): Completable {
        val root = File(meta.path, project.path)
        val files = root.listFiles(FileFilter { it.extension == "usfm" })
        val obs = Observable.fromIterable(files.toList())
        return obs.map {
            //parse each chapter usfm file
            ParseUsfm(it).parse()
        }.flatMap {
            //iterate over each chapter
            Observable.fromIterable(it.chapters.toList())
        }.flatMapSingle {
            // create a collection out of each chapter to store in the database
            val chapter = it
            val ch = Collection(
                    chapter.first,
                    "${meta.language.slug}_${book.slug}_ch${chapter.first}",
                    "chapter",
                    chapter.first.toString(),
                    meta
            )
            return@flatMapSingle collectionRepository.insert(ch).map {
                ch.id = it //set the id allocated by the repository
                return@map Pair(chapter, ch) //return both the chapter and the collection
            }
        }.flatMapSingle {
            //update parent, pass the chapter/collection further down the chain
            collectionRepository.updateParent(it.second, book).toSingle { it }
        }.flatMap {
            val chapter = it.first
            val chapterCollection = it.second
            return@flatMap Observable.fromIterable(chapter.second.values).flatMapSingle {
                //map each verse to a chunk and insert
                val vs = Chunk(
                        it.number,
                        "verse",
                        it.number,
                        it.number,
                        null
                )
                return@flatMapSingle chunkRepository.insertForCollection(vs, chapterCollection)
            }
        }.toList().toCompletable()
    }
}

private fun Project.mapToCollection(type: String, metadata: ResourceMetadata? = null): Collection {
    return Collection(
            sort,
            identifier,
            type,
            title,
            metadata
    )
}