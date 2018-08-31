package org.wycliffeassociates.otter.common.data.model

class Chapter(
        id: Int = 0,
        titleKey: String,
        sort: Int
) : Collection(id, "chapter", titleKey, sort)