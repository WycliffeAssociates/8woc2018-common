package org.wycliffeassociates.otter.common.data.rxmodel

import io.reactivex.Observable

data class Book(
    val sort: Int,
    val title: String,
    val chapters: Observable<Chapter>,
    val hasResources: Boolean
)
