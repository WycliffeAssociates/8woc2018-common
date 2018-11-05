package org.wycliffeassociates.otter.common.domain

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import org.wycliffeassociates.otter.common.data.model.*
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.domain.mapper.mapToMetadata
import org.wycliffeassociates.otter.common.persistence.IDirectoryProvider
import org.wycliffeassociates.otter.common.persistence.repositories.*
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.dublincore
import org.wycliffeassociates.resourcecontainer.entity.language
import org.wycliffeassociates.resourcecontainer.entity.manifest
import java.io.File
import java.time.LocalDate

class CreateProject(
        val languageRepo: ILanguageRepository,
        val sourceRepo: ISourceRepository,
        val collectionRepo: ICollectionRepository,
        val directoryProvider: IDirectoryProvider
) {
    fun getAllLanguages(): Single<List<Language>> {
        return languageRepo.getAll()
    }

    fun getSourceRepos(): Single<List<Collection>> {
        return sourceRepo.getAllRoot()
    }

    fun getAll(): Single<List<Collection>> {
        return collectionRepo.getAll()
    }

    fun newProject(sourceProject: Collection, targetLanguage: Language): Completable {
        // Some concat maps can be removed when dao synchronization is added
        if (sourceProject.resourceContainer == null) throw NullPointerException("Source project has no metadata")
        return collectionRepo.deriveProject(sourceProject, targetLanguage)
    }

    fun getResourceChildren(identifier: SourceCollection): Single<List<Collection>> {
        return sourceRepo.getChildren(identifier)
    }
}