package org.wycliffeassociates.otter.common.data.rxmodel

import io.reactivex.Observable

data class Chapter(
    override val sort: Int,
    override val title: String,
    override val audio: AssociatedAudio,
    override val hasResources: Boolean,
    override val resources: Observable<Resource>,

    val chunks: Observable<Chunk>
) : BookElement
