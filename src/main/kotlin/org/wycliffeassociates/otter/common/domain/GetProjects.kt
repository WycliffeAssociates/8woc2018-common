package org.wycliffeassociates.otter.common.domain

import io.reactivex.Single
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.persistence.repositories.IProjectRepository

class GetProjects(val projectRepo: IProjectRepository) {

    fun getAll(): Single<List<Collection>> {
        return projectRepo.getAll()
    }

    fun getAllRoot(): Single<List<Collection>> {
        return projectRepo.getAllRoot()
    }

    fun getChildren(collection: Collection): Single<List<Collection>> {
        return projectRepo.getChildren(collection)
    }
}