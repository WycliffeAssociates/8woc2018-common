package org.wycliffeassociates.otter.common.data.workbook

import io.reactivex.Observable
import io.reactivex.rxkotlin.cast

data class Chapter(
    override val sort: Int,
    override val title: String,
    override val audio: AssociatedAudio,
    override val resources: List<ResourceGroup>,

    override val subtreeResources: List<ResourceContainerInfo>,

    val chunks: Observable<Chunk>

) : BookElement, HasChildBookElements {

    override val children: Observable<BookElement> = chunks.cast()

}
