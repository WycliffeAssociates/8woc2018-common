package org.wycliffeassociates.otter.common.persistence.repositories

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import org.wycliffeassociates.otter.common.data.model.*
import org.wycliffeassociates.otter.common.data.model.Collection
import org.wycliffeassociates.otter.common.data.workbook.*
import java.time.LocalDate
import java.util.*
import java.util.Collections.synchronizedMap

private typealias ModelTake = org.wycliffeassociates.otter.common.data.model.Take
private typealias WorkbookTake = org.wycliffeassociates.otter.common.data.workbook.Take

class WorkbookRepository(private val db: IDatabaseAccessors) : IWorkbookRepository {
    constructor(
        collectionRepository: ICollectionRepository,
        contentRepository: IContentRepository,
        resourceRepository: IResourceRepository,
        takeRepository: ITakeRepository
    ) : this(
        DefaultDatabaseAccessors(
            collectionRepository,
            contentRepository,
            resourceRepository,
            takeRepository
        )
    )

    /** Disposers for Relays in the current workbook. */
    private val connections = CompositeDisposable()

    override fun get(source: Collection, target: Collection): Workbook {
        // Clear database connections and dispose observables for the
        // previous Workbook if a new one was requested.
        connections.clear()
        return Workbook(
            book(source),
            book(target)
        )
    }

    private fun Collection.getLanguage() = this.resourceContainer?.language
        ?: throw IllegalStateException("Collection with id=$id has null resource container")

    private fun book(bookCollection: Collection): Book {
        return Book(
            title = bookCollection.titleKey,
            sort = bookCollection.sort,
            slug = bookCollection.slug,
            chapters = constructBookChapters(bookCollection),
            language = bookCollection.getLanguage(),
            subtreeResources = db.getSubtreeResourceInfo(bookCollection)
        )
    }

    private fun constructBookChapters(bookCollection: Collection): Observable<Chapter> {
        return Observable.defer {
            db.getChildren(bookCollection)
                .flattenAsObservable { it }
                .concatMapEager { constructChapter(it).toObservable() }
        }.cache()
    }

    private fun constructChapter(chapterCollection: Collection): Single<Chapter> {
        return db.getCollectionMetaContent(chapterCollection)
            .map { metaContent ->
                Chapter(
                    title = chapterCollection.titleKey,
                    sort = chapterCollection.sort,
                    resources = constructResourceGroups(chapterCollection),
                    audio = constructAssociatedAudio(metaContent),
                    chunks = constructChunks(chapterCollection),
                    subtreeResources = db.getSubtreeResourceInfo(chapterCollection)
                )
            }
    }

    private fun constructChunks(chapterCollection: Collection): Observable<Chunk> {
        return Observable.defer {
            db.getContentByCollection(chapterCollection)
                .flattenAsObservable { it }
                .filter { it.type == ContentType.TEXT }
                .map(this::chunk)
        }.cache()
    }

    private fun chunk(content: Content) = Chunk(
        sort = content.sort,
        audio = constructAssociatedAudio(content),
        resources = constructResourceGroups(content),
        textItem = textItem(content),
        start = content.start,
        end = content.end,
        contentType = content.type
    )

    private fun textItem(content: Content): TextItem {
        return content.format?.let { format ->
            // TODO 6/25: Content text should never be null, but parse usfm is currently broken so
            // TODO... only check resource contents for now
            if (listOf(ContentType.TITLE, ContentType.BODY).contains(content.type) && content.text == null) {
                throw IllegalStateException("Content text is null for resource")
            }
            TextItem(content.text ?: "[empty]", MimeType.of(format))
        } ?: TextItem(content.text ?: "[empty]", MimeType.of("usfm")) // TODO 7/5: temporary workaround
//        } ?: throw IllegalStateException("Content format is null")
    }

    private fun constructResource(title: Content, body: Content?): Resource? {
        val bodyComponent = body?.let {
            Resource.Component(
                sort = it.sort,
                textItem = textItem(it),
                audio = constructAssociatedAudio(it),
                contentType = ContentType.BODY
            )
        }

        val titleComponent = Resource.Component(
            sort = title.sort,
            textItem = textItem(title),
            audio = constructAssociatedAudio(title),
            contentType = ContentType.TITLE
        )

        return Resource(
            title = titleComponent,
            body = bodyComponent
        )
    }

    private fun constructResourceGroups(content: Content) = constructResourceGroups(
        resourceInfoList = db.getResourceInfo(content),
        getResourceContents = { db.getResources(content, it) }
    )

    private fun constructResourceGroups(collection: Collection) = constructResourceGroups(
        resourceInfoList = db.getResourceInfo(collection),
        getResourceContents = { db.getResources(collection, it) }
    )

    private fun constructResourceGroups(
        resourceInfoList: List<ResourceInfo>,
        getResourceContents: (ResourceInfo) -> Observable<Content>
    ): List<ResourceGroup> {
        return resourceInfoList.map {
            val resources = Observable.defer {
                getResourceContents(it)
                    .contentsToResources()
            }.cache()

            ResourceGroup(it, resources)
        }
    }

    private fun Observable<Content>.contentsToResources(): Observable<Resource> {
        return this
            .buffer(2, 1) // create a rolling window of size 2
            .concatMapIterable { list ->
                val a = list.getOrNull(0)
                val b = list.getOrNull(1)
                listOfNotNull(
                    when {
                        // If the first element isn't a title, skip this pair, because the body
                        // was already used by the previous window.
                        a?.type != ContentType.TITLE -> null

                        // If the second element isn't a body, just use the title. (The second
                        // element will appear again in the next window.)
                        b?.type != ContentType.BODY -> constructResource(a, null)

                        // Else, we have a title/body pair, so use it.
                        else -> constructResource(a, b)
                    }
                )
            }
    }

    /** Build a relay primed with the current deletion state, that responds to updates by writing to the DB. */
    private fun deletionRelay(modelTake: ModelTake): BehaviorRelay<DateHolder> {
        val relay = BehaviorRelay.createDefault(DateHolder(modelTake.deleted))

        val subscription = relay
            .skip(1) // ignore the initial value
            .subscribe {
                db.deleteTake(modelTake, it)
            }

        connections += subscription
        return relay
    }

    /** Build a relay primed with the current modified timestamp, that responds to updates by writing to the DB. */
    private fun modifiedRelay(modelTake: ModelTake): BehaviorRelay<LocalDate> {
        val relay = BehaviorRelay.createDefault(modelTake.created)

        val subscription = relay
            .skip(1) // ignore the initial value
            .doOnError {

            }
            .subscribe {
                db.editTake(modelTake, it)
            }

        connections += subscription
        return relay
    }

    private fun deselectUponDelete(take: WorkbookTake, selectedTakeRelay: BehaviorRelay<TakeHolder>) {
        val subscription = take.deletedTimestamp
            .filter { localDate -> localDate.value != null }
            .filter { take == selectedTakeRelay.value?.value }
            .map { TakeHolder(null) }
            .subscribe(selectedTakeRelay)
        connections += subscription
    }

    private fun workbookTake(modelTake: ModelTake): WorkbookTake {
        return WorkbookTake(
            name = modelTake.filename,
            file = modelTake.path,
            number = modelTake.number,
            format = MimeType.WAV, // TODO
            modifiedTimestamp = modifiedRelay(modelTake),
            deletedTimestamp = deletionRelay(modelTake)
        )
    }

    private fun modelTake(workbookTake: WorkbookTake, markers: List<Marker> = listOf()): ModelTake {
        return ModelTake(
            filename = workbookTake.file.name,
            path = workbookTake.file,
            number = workbookTake.number,
            created = workbookTake.modifiedTimestamp.value
                ?: throw IllegalStateException("Take ${workbookTake.file.name} has null modified timestamp"),
            deleted = null,
            played = false,
            markers = markers
        )
    }

    private fun constructAssociatedAudio(content: Content): AssociatedAudio {
        /** Map to recover model.Take objects from workbook.Take objects. */
        val takeMap = synchronizedMap(WeakHashMap<WorkbookTake, ModelTake>())

        /** The initial selected take, from the DB. */
        val initialSelectedTake = TakeHolder(content.selectedTake?.let { workbookTake(it) })

        /** Relay to send selected-take updates out to consumers, but also receive updates from UI. */
        val selectedTakeRelay = BehaviorRelay.createDefault(initialSelectedTake)

        // When we receive an update, write it to the DB.
        val selectedTakeRelaySubscription = selectedTakeRelay
            .distinctUntilChanged() // Don't write unless changed
            .skip(1) // Don't write the value we just loaded from the DB
            .subscribe {
                content.selectedTake = it.value?.let { wbTake -> takeMap[wbTake] }
                db.updateContent(content)
            }

        /** Initial Takes read from the DB. */
        val takesFromDb = db.getTakeByContent(content)
            .flattenAsObservable { list: List<ModelTake> -> list.sortedBy { it.number } }
            .map { workbookTake(it) to it }

        /** Relay to send Takes out to consumers, but also receive new Takes from UI. */
        val takesRelay = ReplayRelay.create<WorkbookTake>()
        takesFromDb
            // Record the mapping between data types.
            .doOnNext { (wbTake, modelTake) -> takeMap[wbTake] = modelTake }
            // Feed the initial list to takesRelay
            .map { (wbTake, _) -> wbTake }
            .subscribe(takesRelay)

        val takesRelaySubscription = takesRelay
            // When the selected take becomes deleted, deselect it.
            .doOnNext { deselectUponDelete(it, selectedTakeRelay) }

            // Keep the takeMap current.
            .filter { !takeMap.contains(it) } // don't duplicate takes
            .map { it to modelTake(it) }
            .doOnNext { (wbTake, modelTake) -> takeMap[wbTake] = modelTake }

            // Insert the new take into the DB.
            .subscribe { (_, modelTake) ->
                db.insertTakeForContent(modelTake, content)
                    .subscribe { insertionId -> modelTake.id = insertionId }
            }

        connections += takesRelaySubscription
        connections += selectedTakeRelaySubscription
        return AssociatedAudio(takesRelay, selectedTakeRelay)
    }

    interface IDatabaseAccessors {
        fun getChildren(collection: Collection): Single<List<Collection>>
        fun getCollectionMetaContent(collection: Collection): Single<Content>
        fun getContentByCollection(collection: Collection): Single<List<Content>>
        fun updateContent(content: Content): Completable
        fun getResources(content: Content, info: ResourceInfo): Observable<Content>
        fun getResources(collection: Collection, info: ResourceInfo): Observable<Content>
        fun getResourceInfo(content: Content): List<ResourceInfo>
        fun getResourceInfo(collection: Collection): List<ResourceInfo>
        fun getSubtreeResourceInfo(collection: Collection): List<ResourceInfo>
        fun insertTakeForContent(take: ModelTake, content: Content): Single<Int>
        fun getTakeByContent(content: Content): Single<List<ModelTake>>
        fun deleteTake(take: ModelTake, date: DateHolder): Completable
        fun editTake(take: ModelTake, date: LocalDate): Completable
    }
}

private class DefaultDatabaseAccessors(
    private val collectionRepo: ICollectionRepository,
    private val contentRepo: IContentRepository,
    private val resourceRepo: IResourceRepository,
    private val takeRepo: ITakeRepository
) : WorkbookRepository.IDatabaseAccessors {
    override fun getChildren(collection: Collection) = collectionRepo.getChildren(collection)

    override fun getCollectionMetaContent(collection: Collection) = contentRepo.getCollectionMetaContent(collection)
    override fun getContentByCollection(collection: Collection) = contentRepo.getByCollection(collection)
    override fun updateContent(content: Content) = contentRepo.update(content)

    override fun getResources(content: Content, info: ResourceInfo) = resourceRepo.getResources(content, info)
    override fun getResources(collection: Collection, info: ResourceInfo) = resourceRepo.getResources(collection, info)
    override fun getResourceInfo(content: Content) = resourceRepo.getResourceInfo(content)
    override fun getResourceInfo(collection: Collection) = resourceRepo.getResourceInfo(collection)
    override fun getSubtreeResourceInfo(collection: Collection) = resourceRepo.getSubtreeResourceInfo(collection)

    override fun insertTakeForContent(take: ModelTake, content: Content) = takeRepo.insertForContent(take, content)
    override fun getTakeByContent(content: Content) = takeRepo.getByContent(content)
    override fun deleteTake(take: ModelTake, date: DateHolder) = takeRepo.update(take.copy(deleted = date.value))
    override fun editTake(take: ModelTake, date: LocalDate) = takeRepo.update(take.copy(created = date))
}
