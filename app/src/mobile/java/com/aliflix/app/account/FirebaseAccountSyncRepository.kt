package com.aliflix.app.account

import com.aliflix.app.model.PlaybackProviderId

import android.content.Context
import com.aliflix.app.data.LibraryMutation
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.data.PlaybackProgressMutation
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.data.RamoflixConfig
import com.aliflix.app.data.isValidPlaybackProgress
import com.aliflix.app.data.mergePlaybackProgress
import com.aliflix.app.model.Media
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.model.defaultGeneralPlaybackProvider
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.recommendation.RecommendationStore
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.Timestamp
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class FirebaseAccountSyncRepository(
    context: Context,
    private val accountRepository: AccountRepository,
    private val libraryStore: LibraryStore,
    private val playbackRepository: PlaybackProviderRepository,
    private val playbackProgressStore: PlaybackProgressStore,
    private val recommendationStore: RecommendationStore,
    parentScope: CoroutineScope,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : AccountSyncRepository {
    private val appContext=context.applicationContext
    private val repositoryJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(repositoryJob + Dispatchers.Main.immediate)
    private val recentOutbox = context.getSharedPreferences("recent-play-outbox", Context.MODE_PRIVATE)
    private val libraryOutbox = context.getSharedPreferences("account-library-outbox", Context.MODE_PRIVATE)
    private val snapshotStore = AccountLocalSnapshotStore(context)
    private val _state = MutableStateFlow<AccountSyncState>(AccountSyncState.SignedOut)
    override val state: StateFlow<AccountSyncState> = _state.asStateFlow()
    private val retrySignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val confirmedProgress = mutableMapOf<String, PlaybackProgress>()
    private val deletingUid = MutableStateFlow<String?>(null)
    private val pausedDeletionUid = MutableStateFlow<String?>(null)

    private val playerStateSync = FirebasePlayerStateSync(context, accountRepository, scope, deletingUid, firestore) { error ->
        _state.value = AccountSyncState.Error(syncErrorMessage(error))
    }

    init {
        scope.launch {
            combine(
                accountRepository.state.map { it.uid }.distinctUntilChanged(),
                deletingUid,
            ) { uid, deleting -> uid to deleting }
                .distinctUntilChanged()
                .collectLatest { (uid, deleting) ->
                    switchLocalScope(uid)
                    recommendationStore.migrateCloudflareDefaultForScope(uid?.let(AccountMergePolicy::userScope) ?: AccountMergePolicy.GUEST_SCOPE)
                    when {
                        uid == null -> {
                            pausedDeletionUid.value = null
                            deletingUid.value = null
                            _state.value = AccountSyncState.SignedOut
                        }
                        uid == deleting -> {
                            pausedDeletionUid.value = uid
                            _state.value = AccountSyncState.Syncing
                            awaitCancellation()
                        }
                        else -> {
                            pausedDeletionUid.value = null
                            runSignedInSession(uid)
                        }
                    }
                }
        }
    }

    override fun retry() {
        retrySignal.trySend(Unit)
    }

    override suspend fun deleteCloudAccountData(uid: String): AccountActionResult {
        if (accountRepository.uid != uid) {
            return AccountActionResult(false, "The signed-in account changed. Please try again.")
        }
        deletingUid.value = uid
        return try {
            withTimeout(DELETION_TIMEOUT_MILLIS) {
                pausedDeletionUid.first { it == uid }
                com.aliflix.app.talk.TalkAccountArchive.deleteAccount(appContext,uid)
                val user = userDocument(uid)
                AccountCloudDeletionPlan.collections.forEach { collection ->
                    deleteCollection(user.collection(collection))
                }
                val remaining = AccountCloudDeletionPlan.collections.firstOrNull { collection ->
                    !user.collection(collection).limit(1).get(Source.SERVER).await().isEmpty
                }
                check(remaining == null) {
                    "Cloud account data could not be fully removed."
                }
            }
            AccountActionResult(succeeded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            deletingUid.value = null
            val message = when (error) {
                is FirebaseFirestoreException -> syncErrorMessage(error)
                else -> "Account data could not be deleted. Nothing on this device was removed."
            }
            _state.value = AccountSyncState.Error(message)
            AccountActionResult(succeeded = false, message = message)
        }
    }

    override suspend fun finishAccountDeletion(uid: String) {
        withContext(Dispatchers.Main.immediate) {
            val deletedScope = AccountMergePolicy.userScope(uid)
            if (snapshotStore.activeScope == deletedScope) {
                restoreGuestScope()
            }
            snapshotStore.remove(deletedScope)
            playerStateSync.forget(uid)
            recentOutbox.edit().also { editor -> recentOutbox.all.keys.filter { it.startsWith("$uid/") }.forEach { editor.remove(it) } }.apply()
            libraryOutbox.edit().also { editor -> libraryOutbox.all.keys.filter { it.startsWith("$uid/") }.forEach(editor::remove) }.apply()
            pausedDeletionUid.value = null
            deletingUid.value = null
            _state.value = AccountSyncState.SignedOut
        }
    }

    override suspend fun resumeAfterFailedAccountDeletion(uid: String) {
        com.aliflix.app.talk.TalkAccountArchive.get(appContext).refresh()
        withContext(Dispatchers.Main.immediate) {
            if (deletingUid.value == uid) {
                pausedDeletionUid.value = null
                deletingUid.value = null
                retrySignal.trySend(Unit)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        saveActiveLocalSnapshot()
        scope.cancel()
    }

    private suspend fun runSignedInSession(uid: String) = coroutineScope {
        val mutationJobs = listOf(
            launch { collectLibraryMutations(uid) },
            launch { collectSettingsMutations(uid) },
            launch { collectPlaybackProgressMutations(uid) },
            launch {
                while (true) {
                    kotlinx.coroutines.delay(PROGRESS_CLOUD_INTERVAL_MILLIS)
                    playbackProgressStore.snapshot().forEach { writeProgressIfNewer(uid, it).observeWriteResult(uid) }
                    libraryOutbox.all.filterKeys { it.startsWith("$uid/") }.forEach { (key,raw) ->
                        val path=key.removePrefix("$uid/")
                        runCatching { replayLibraryChange(uid,path.substringBefore('/'),path.substringAfter('/'),raw as String).observeWriteResult(uid) }
                    }
                    recentOutbox.all.filterKeys { it.startsWith("$uid/") }.forEach { (_, raw) ->
                        runCatching {
                            val json = org.json.JSONObject(raw as String)
                            val entry = RecentMediaEntry(Media.fromJson(json.getJSONObject("media")), json.getLong("time"))
                            writeRecentIfNewer(uid, entry).observeWriteResult(uid)
                        }
                    }
                }
            },
        )
        val listeners = mutableListOf<ListenerRegistration>()
        var retryDelayMillis = 2_000L
        try {
            while (true) {
                listeners.forEach(ListenerRegistration::remove)
                listeners.clear()
                _state.value = AccountSyncState.Syncing
                try {
                    reconcileInitialState(uid) { listeners += attachRemoteListeners(uid) }
                    _state.value = AccountSyncState.Synced
                    retryDelayMillis = 2_000L
                    retrySignal.receive()
                    continue
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    _state.value = AccountSyncState.Error(syncErrorMessage(cancelled))
                    withTimeoutOrNull(retryDelayMillis) { retrySignal.receive() }
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(60_000L)
                } catch (error: Exception) {
                    _state.value = AccountSyncState.Error(syncErrorMessage(error))
                    val manuallyRetried = withTimeoutOrNull(retryDelayMillis) {
                        retrySignal.receive()
                    } != null
                    if (!manuallyRetried) {
                        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(60_000L)
                    }
                }
            }
        } finally {
            listeners.forEach(ListenerRegistration::remove)
            mutationJobs.forEach(Job::cancel)
            saveActiveLocalSnapshot()
        }
    }

    private suspend fun reconcileInitialState(uid: String, onHydrated: () -> Unit) {
        val cloud = withTimeout(INITIAL_SYNC_TIMEOUT_MILLIS) { fetchCloudSnapshot(uid) }
        val mergedLibrary = AccountMergePolicy.mergeLibrary(libraryStore.snapshot(), cloud.library).let { merged -> merged.copy(
            myList=overlayLibraryOutbox(uid,MY_LIST,merged.myList.filterNot { it.key in cloud.removedMyList }),
            favorites=overlayLibraryOutbox(uid,FAVORITES,merged.favorites.filterNot { it.key in cloud.removedFavorites }),
            recent=merged.recent.filterNot { (cloud.removedRecent[it.media.key]?:-1)>=it.lastPlayedAtMillis || pendingDeletion(uid,RECENT,it.media.key,it.lastPlayedAtMillis) },
        ) }
        val mergedProgress = mergePlaybackProgress(playbackProgressStore.snapshot(), cloud.playbackProgress)
            .filterNot { (cloud.removedProgress[it.key]?:-1)>=it.updatedAtMillis || pendingDeletion(uid,PROGRESS,it.key,it.updatedAtMillis) }
        val localSettings = currentSettingsSnapshot()
        val mergedSettings = AccountMergePolicy.resolveSettings(localSettings, cloud.settings)
        libraryStore.applySyncedSnapshot(mergedLibrary)
        playbackProgressStore.applySyncedEntries(mergedProgress)
        applySettingsSnapshot(mergedSettings)
        saveActiveLocalSnapshot()
        // Subscribe before uploading the backlog; hundreds of sequential progress
        // transactions must not delay changes arriving from another device.
        onHydrated()

        withTimeout(INITIAL_SYNC_TIMEOUT_MILLIS) {
            uploadMergedSnapshot(uid, mergedLibrary, mergedProgress, mergedSettings)
        }
    }

    private suspend fun fetchCloudSnapshot(uid: String): CloudAccountSnapshot = coroutineScope {
        val user = userDocument(uid)
        val myList = async { user.collection(MY_LIST).get().await() }
        val favorites = async { user.collection(FAVORITES).get().await() }
        val recent = async { user.collection(RECENT).get().await() }
        val playbackProgress = async { user.collection(PROGRESS).get().await() }
        val settings = async {
            user.collection(SETTINGS).document(MAIN_DOCUMENT).get().await()
                .takeIf(DocumentSnapshot::exists)
                ?.toSettingsSnapshot()
        }
        CloudAccountSnapshot(
            library = LibrarySnapshot(
                myList = myList.await().toMediaList(),
                favorites = favorites.await().toMediaList(),
                recent = recent.await().toRecentList(),
            ),
            playbackProgress = playbackProgress.await().toPlaybackProgressList(),
            settings = settings.await(),
            removedMyList=myList.await().tombstones().keys,
            removedFavorites=favorites.await().tombstones().keys,
            removedRecent=recent.await().tombstones(),
            removedProgress=playbackProgress.await().tombstones(),
        )
    }

    private suspend fun uploadMergedSnapshot(
        uid: String,
        library: LibrarySnapshot,
        playbackProgress: List<PlaybackProgress>,
        settings: AccountSettingsSnapshot,
    ) {
        val user = userDocument(uid)
        val writes = mutableListOf<PendingSet>()
        library.myList.forEach { media ->
            seedMediaIfAbsent(user.collection(MY_LIST).document(media.key),media).await()
        }
        library.favorites.forEach { media ->
            seedMediaIfAbsent(user.collection(FAVORITES).document(media.key),media).await()
        }
        library.recent.forEach { entry -> writeRecentIfNewer(uid, entry).await() }
        playbackProgress.forEach { progress ->
            writeProgressIfNewer(uid, progress).await()
        }
        writeSettingsIfNewer(uid,settings).await()
        accountRepository.state.value.user?.let { account ->
            writes += PendingSet(
                reference = user.collection(PROFILE).document(MAIN_DOCUMENT),
                data = mapOf(
                    "uid" to account.uid,
                    "email" to account.email,
                    "photoUrl" to account.photoUrl,
                    "providerIds" to account.providerIds.sorted(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                merge = true,
            )
        }
        writes.chunked(MAX_BATCH_WRITES).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { write ->
                if (write.merge) {
                    batch.set(write.reference, write.data, SetOptions.merge())
                } else {
                    batch.set(write.reference, write.data)
                }
            }
            batch.commit().await()
        }
    }

    private fun attachRemoteListeners(uid: String): List<ListenerRegistration> {
        val user = userDocument(uid)
        return listOf(
            user.collection(MY_LIST).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                handleCollectionSnapshot(uid, snapshot, error) { media ->
                    libraryStore.applySyncedSnapshot(libraryStore.snapshot().copy(myList = overlayLibraryOutbox(uid,MY_LIST,media)))
                }
            },
            user.collection(FAVORITES).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                handleCollectionSnapshot(uid, snapshot, error) { media ->
                    libraryStore.applySyncedSnapshot(libraryStore.snapshot().copy(favorites = overlayLibraryOutbox(uid,FAVORITES,media)))
                }
            },
            user.collection(RECENT).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!validRemoteSnapshot(uid, snapshot, error)) return@addSnapshotListener
                libraryStore.applySyncedSnapshot(
                    libraryStore.snapshot().copy(recent = AccountMergePolicy.mergeRecent(
                        libraryStore.recentEntries.value.filter { recentOutbox.contains("$uid/${it.media.key}") },
                        snapshot!!.toRecentList(),
                    ).filterNot { pendingDeletion(uid,RECENT,it.media.key,it.lastPlayedAtMillis) }),
                )
                saveActiveLocalSnapshot()
                _state.value = AccountSyncState.Synced
            },
            user.collection(PROGRESS).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!validRemoteSnapshot(uid, snapshot, error)) return@addSnapshotListener
                playbackProgressStore.applySyncedEntries(
                    mergePlaybackProgress(
                        playbackProgressStore.snapshot().filterNot { (snapshot!!.tombstones()[it.key]?:-1)>=it.updatedAtMillis || pendingDeletion(uid,PROGRESS,it.key,it.updatedAtMillis) },
                        snapshot!!.toPlaybackProgressList(),
                    ).filterNot { pendingDeletion(uid,PROGRESS,it.key,it.updatedAtMillis) },
                )
                saveActiveLocalSnapshot()
                _state.value = AccountSyncState.Synced
            },
            user.collection(SETTINGS).document(MAIN_DOCUMENT)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (accountRepository.uid != uid) return@addSnapshotListener
                    if (error != null) {
                        _state.value = AccountSyncState.Error(syncErrorMessage(error))
                        return@addSnapshotListener
                    }
                    if (
                        snapshot == null ||
                        snapshot.metadata.hasPendingWrites()
                    ) return@addSnapshotListener
                    val remote = snapshot.takeIf(DocumentSnapshot::exists)?.toSettingsSnapshot()
                        ?: return@addSnapshotListener
                    val resolved = AccountMergePolicy.resolveSettings(
                        currentSettingsSnapshot(),
                        remote,
                    )
                    applySettingsSnapshot(resolved)
                    saveActiveLocalSnapshot()
                    _state.value = AccountSyncState.Synced
                },
        )
    }

    private fun handleCollectionSnapshot(
        uid: String,
        snapshot: QuerySnapshot?,
        error: FirebaseFirestoreException?,
        apply: (List<Media>) -> Unit,
    ) {
        if (!validRemoteSnapshot(uid, snapshot, error)) return
        apply(snapshot!!.toMediaList())
        saveActiveLocalSnapshot()
        _state.value = AccountSyncState.Synced
    }

    private fun validRemoteSnapshot(
        uid: String,
        snapshot: QuerySnapshot?,
        error: FirebaseFirestoreException?,
    ): Boolean {
        if (accountRepository.uid != uid) return false
        if (error != null) {
            _state.value = AccountSyncState.Error(syncErrorMessage(error))
            return false
        }
        return snapshot != null && !snapshot.metadata.hasPendingWrites()
    }

    private suspend fun collectLibraryMutations(uid: String) {
        libraryStore.mutations.collect { mutation ->
            if (accountRepository.uid != uid) return@collect
            saveActiveLocalSnapshot()
            when (mutation) {
                is LibraryMutation.MyListChanged -> {
                    writeLibraryChange(uid,MY_LIST,mutation.media,mutation.added,mutation.metadataOnly)
                }
                is LibraryMutation.FavoriteChanged -> {
                    writeLibraryChange(uid,FAVORITES,mutation.media,mutation.added,mutation.metadataOnly)
                }
                is LibraryMutation.RecentPlayed -> writeRecentIfNewer(uid, mutation.entry, mutation.metadataOnly)
                is LibraryMutation.RecentRemoved -> {
                    recentOutbox.edit().remove("$uid/${mutation.mediaKey}").apply()
                    writeDeletion(uid,RECENT,mutation.mediaKey)
                }
                is LibraryMutation.RecentCleared -> {
                    recentOutbox.edit().also { editor -> recentOutbox.all.keys.filter { it.startsWith("$uid/") }.forEach(editor::remove) }.apply()
                    com.google.android.gms.tasks.Tasks.whenAll(mutation.mediaKeys.map { writeDeletion(uid,RECENT,it) })
                }
            }.observeWriteResult(uid)
        }
    }

    private fun writeRecentIfNewer(uid: String, entry: RecentMediaEntry, metadataOnly: Boolean = false): Task<*> {
        val outboxKey = "$uid/${entry.media.key}"
        if (!metadataOnly) {
            val raw = org.json.JSONObject().put("media", entry.media.toJson()).put("time", entry.lastPlayedAtMillis).toString()
            recentOutbox.edit().putString(outboxKey, raw).apply()
        }
        val document = userDocument(uid).collection(RECENT).document(entry.media.key)
        return firestore.runTransaction { transaction ->
            val existing = transaction.get(document)
            if(existing.getBoolean("deleted")==true&&(existing.getLong("updatedAtMillis")?:0)>=entry.lastPlayedAtMillis)return@runTransaction
            val lastPlayed = existing.getLong("lastPlayedAtMillis") ?: existing.getTimestamp("lastPlayedAt")?.toDate()?.time ?: 0L
            if (metadataOnly) {
                if (existing.exists()) transaction.set(document, mediaDocument(entry.media), SetOptions.merge())
            } else if (entry.lastPlayedAtMillis >= lastPlayed) {
                transaction.set(document, recentDocument(entry, useServerTimestamp = false))
            }
        }.addOnSuccessListener {
            val pending = recentOutbox.getString(outboxKey, null)
            if (!metadataOnly && pending != null && org.json.JSONObject(pending).optLong("time") == entry.lastPlayedAtMillis) {
                recentOutbox.edit().remove(outboxKey).apply()
            }
        }
    }

    private suspend fun collectSettingsMutations(uid: String) {
        merge(playbackRepository.mutations, recommendationStore.mutations).collect {
            if (accountRepository.uid != uid) return@collect
            val settings = currentSettingsSnapshot()
            saveActiveLocalSnapshot()
            writeSettingsIfNewer(uid,settings).observeWriteResult(uid)
        }
    }

    private suspend fun collectPlaybackProgressMutations(uid: String) {
        val lastCloudWriteAt = mutableMapOf<String, Long>()
        playbackProgressStore.mutations.collect { mutation ->
            if (accountRepository.uid != uid) return@collect
            saveActiveLocalSnapshot()
            when (mutation) {
                is PlaybackProgressMutation.Changed -> {
                    val progress = mutation.progress
                    val lastWrite = lastCloudWriteAt[progress.key] ?: 0L
                    if (
                        mutation.urgentCloudSync ||
                        progress.updatedAtMillis - lastWrite >= PROGRESS_CLOUD_INTERVAL_MILLIS
                    ) {
                        lastCloudWriteAt[progress.key] = progress.updatedAtMillis
                        writeProgressIfNewer(uid, progress).observeWriteResult(uid)
                    }
                }
                is PlaybackProgressMutation.Removed -> {
                    mutation.progressKeys.forEach { key ->
                        lastCloudWriteAt.remove(key)
                        confirmedProgress.remove("$uid:$key")
                        writeDeletion(uid,PROGRESS,key).observeWriteResult(uid)
                    }
                }
            }
        }
    }

    // Compare playback timestamps atomically. Offline state is already durable in the
    // existing progress store and retried by this session/startup, never a blind queued set.
    private fun writeProgressIfNewer(uid: String, progress: PlaybackProgress): Task<Boolean> {
        val cacheKey = "$uid:${progress.key}"
        if (confirmedProgress[cacheKey] == progress) return com.google.android.gms.tasks.Tasks.forResult(false)
        val document = userDocument(uid).collection(PROGRESS).document(progress.key)
        return firestore.runTransaction { transaction ->
            val snapshot=transaction.get(document)
            if(snapshot.getBoolean("deleted")==true&&(snapshot.getLong("updatedAtMillis")?:0)>=progress.updatedAtMillis)return@runTransaction false
            val remote = snapshot.toPlaybackProgress()
            val winner = mergePlaybackProgress(listOf(progress), listOfNotNull(remote)).firstOrNull()
            if (winner == progress && remote != progress) {
                transaction.set(document, progressDocument(progress, useServerTimestamp = false))
                true
            } else false
        }.addOnSuccessListener {
            if (accountRepository.uid == uid) confirmedProgress[cacheKey] = progress
        }
    }

    private fun Task<*>.observeWriteResult(uid: String): Task<*> =
        addOnFailureListener { error ->
            if (accountRepository.uid == uid) {
                _state.value = AccountSyncState.Error(syncErrorMessage(error))
            }
        }.addOnSuccessListener {
            if (accountRepository.uid == uid) _state.value = AccountSyncState.Synced
        }

    private fun switchLocalScope(targetUid: String?) {
        val activeScope = snapshotStore.activeScope
        val targetScope = targetUid?.let(AccountMergePolicy::userScope)
            ?: AccountMergePolicy.GUEST_SCOPE
        if (activeScope == targetScope) return
        snapshotStore.save(activeScope, currentLocalSnapshot())
        val transition = AccountMergePolicy.scopeTransition(
            activeScope = activeScope,
            targetUid = targetUid,
            existingUserScopes = snapshotStore.existingUserScopes(),
        )
        val restored = snapshotStore.load(transition.scopeToRestore)
        if (restored != null) {
            libraryStore.applySyncedSnapshot(restored.library)
            playbackProgressStore.applySyncedEntries(restored.playbackProgress)
            applySettingsSnapshot(restored.settings)
        } else {
            libraryStore.applySyncedSnapshot(LibrarySnapshot())
            playbackProgressStore.applySyncedEntries(emptyList())
        }
        snapshotStore.setActiveScope(targetScope)
        snapshotStore.save(targetScope, currentLocalSnapshot())
    }

    private fun restoreGuestScope() {
        val guest = snapshotStore.load(AccountMergePolicy.GUEST_SCOPE)
        if (guest != null) {
            libraryStore.applySyncedSnapshot(guest.library)
            playbackProgressStore.applySyncedEntries(guest.playbackProgress)
            applySettingsSnapshot(guest.settings)
        } else {
            libraryStore.applySyncedSnapshot(LibrarySnapshot())
            playbackProgressStore.applySyncedEntries(emptyList())
        }
        snapshotStore.setActiveScope(AccountMergePolicy.GUEST_SCOPE)
        snapshotStore.save(AccountMergePolicy.GUEST_SCOPE, currentLocalSnapshot())
    }

    private suspend fun deleteCollection(collection: com.google.firebase.firestore.CollectionReference) {
        while (true) {
            val snapshot = collection.limit(MAX_BATCH_WRITES.toLong()).get(Source.SERVER).await()
            if (snapshot.isEmpty) return
            val batch = firestore.batch()
            snapshot.documents.forEach { document -> batch.delete(document.reference) }
            batch.commit().await()
        }
    }

    private fun saveActiveLocalSnapshot() {
        snapshotStore.save(snapshotStore.activeScope, currentLocalSnapshot())
    }

    private fun currentLocalSnapshot() = AccountLocalSnapshot(
        library = libraryStore.snapshot(),
        playbackProgress = playbackProgressStore.snapshot(),
        settings = currentSettingsSnapshot(),
    )

    private fun currentSettingsSnapshot(): AccountSettingsSnapshot {
        val playback = playbackRepository.preferences.value
        return AccountSettingsSnapshot(
            generalProvider = playback.effectiveGeneralProvider.name,
            ramoflixUrl = playback.ramoflixConfig.baseUrl,
            moviepireUrl = playback.moviepireBaseUrl,
            dorabyUrl = playback.dorabyBaseUrl,
            askAliflixEnabled = recommendationStore.enabled.value,
            recommendationAiModel = recommendationStore.aiModel.value.workerValue,
            updatedAtMillis = maxOf(
                playbackRepository.updatedAtMillis.value,
                recommendationStore.updatedAtMillis.value,
            ),
            preferredSubtitleLanguage = playback.preferredSubtitleLanguage.code,
            autoDisplaySubtitles = playback.autoDisplaySubtitles,
            hasExplicitLocalValues = playbackRepository.hasExplicitValues ||
                recommendationStore.hasExplicitValues,
        )
    }

    private fun applySettingsSnapshot(settings: AccountSettingsSnapshot) {
        val playback = PlaybackPreferences(
            generalProvider = PlaybackProvider.fromStoredValue(settings.generalProvider)
                ?: defaultGeneralPlaybackProvider(isTv = false),
            ramoflixConfig = RamoflixConfig(
                RamoflixConfig.normalizeBaseUrl(settings.ramoflixUrl)
                    ?: RamoflixConfig.DEFAULT_URL,
            ),
            moviepireBaseUrl = RamoflixConfig.normalizeBaseUrl(settings.moviepireUrl)
                ?: PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
            dorabyBaseUrl = RamoflixConfig.normalizeBaseUrl(settings.dorabyUrl)
                ?: PlaybackProviderId.DORABY.defaultBaseUrl,
            preferredSubtitleLanguage = SubtitleLanguage.fromCode(
                settings.preferredSubtitleLanguage,
            ),
            autoDisplaySubtitles = settings.autoDisplaySubtitles,
        )
        playbackRepository.applySyncedPreferences(playback, settings.updatedAtMillis)
        recommendationStore.applySyncedSettings(
            enabled = settings.askAliflixEnabled,
            model = RecommendationAiModel.fromWorkerValue(settings.recommendationAiModel),
            updatedAtMillis = settings.updatedAtMillis,
        )
    }

    private fun userDocument(uid: String) = firestore.collection(USERS).document(uid)
    private fun pendingDeletion(uid:String,collection:String,id:String,time:Long):Boolean = libraryOutbox.getString("$uid/$collection/$id",null)?.let {
        val json=org.json.JSONObject(it);json.optBoolean("deleted")&&json.optLong("time")>=time
    }?:false
    private fun writeDeletion(uid:String,collection:String,id:String):Task<*> {
        val raw=org.json.JSONObject().put("time",System.currentTimeMillis()).put("deleted",true).toString()
        libraryOutbox.edit().putString("$uid/$collection/$id",raw).apply()
        return replayLibraryChange(uid,collection,id,raw)
    }
    private fun overlayLibraryOutbox(uid:String,collection:String,remote:List<Media>):List<Media> {
        val items=remote.associateBy { it.key }.toMutableMap()
        libraryOutbox.all.filterKeys { it.startsWith("$uid/$collection/") }.forEach { (key,raw) -> runCatching {
            val json=org.json.JSONObject(raw as String);val id=key.substringAfterLast('/')
            if(json.optBoolean("deleted"))items.remove(id) else items[id]=Media.fromJson(json.getJSONObject("media"))
        } }
        return items.values.toList()
    }
    private fun writeLibraryChange(uid:String,collection:String,media:Media,added:Boolean,metadataOnly:Boolean):Task<*> {
        val document=userDocument(uid).collection(collection).document(media.key)
        if(metadataOnly)return firestore.runTransaction { transaction ->
            val remote=transaction.get(document)
            if(remote.exists()&&remote.getBoolean("deleted")!=true)transaction.set(document,mediaDocument(media),SetOptions.merge())
        }
        val key="$uid/$collection/${media.key}"
        val previous=libraryOutbox.getString(key,null)?.let { org.json.JSONObject(it).optLong("time") }?:0L
        val raw=org.json.JSONObject().put("time",maxOf(System.currentTimeMillis(),previous+1)).put("deleted",!added).put("media",media.toJson()).toString()
        libraryOutbox.edit().putString(key,raw).apply()
        return replayLibraryChange(uid,collection,media.key,raw)
    }
    private fun replayLibraryChange(uid:String,collection:String,id:String,raw:String):Task<*> {
        val json=org.json.JSONObject(raw);val time=json.getLong("time");val document=userDocument(uid).collection(collection).document(id)
        return firestore.runTransaction { transaction ->
            val remote=transaction.get(document)
            if(maxOf(remote.getLong("updatedAtMillis")?:0,remote.getLong("lastPlayedAtMillis")?:0)>time)remote else {
                val data=if(json.getBoolean("deleted"))deletionDocument() else mediaDocument(Media.fromJson(json.getJSONObject("media")))
                transaction.set(document,data+mapOf("updatedAtMillis" to time));null
            }
        }.addOnSuccessListener { winner ->
            if(libraryOutbox.getString("$uid/$collection/$id",null)==raw) {
                libraryOutbox.edit().remove("$uid/$collection/$id").apply()
                if(winner!=null&&accountRepository.uid==uid) {
                    val current=libraryStore.snapshot()
                    when(collection) {
                        MY_LIST,FAVORITES -> {
                            val list=if(collection==MY_LIST)current.myList else current.favorites
                            val winning=list.filterNot { it.key==id }+listOfNotNull(winner.toMedia())
                            libraryStore.applySyncedSnapshot(if(collection==MY_LIST)current.copy(myList=winning)else current.copy(favorites=winning))
                        }
                        RECENT -> winner.toMedia()?.let { media -> libraryStore.applySyncedSnapshot(current.copy(recent=AccountMergePolicy.mergeRecent(current.recent,listOf(RecentMediaEntry(media,winner.getLong("lastPlayedAtMillis")?:0))))) }
                        PROGRESS -> winner.toPlaybackProgress()?.let { playbackProgressStore.applySyncedEntries(mergePlaybackProgress(playbackProgressStore.snapshot(),listOf(it))) }
                    }
                }
            }
        }
    }
    private fun deletionDocument()=mapOf("deleted" to true,"updatedAtMillis" to System.currentTimeMillis(),"updatedAt" to FieldValue.serverTimestamp())
    private fun seedMediaIfAbsent(document:DocumentReference,media:Media)=firestore.runTransaction { transaction ->
        if(!transaction.get(document).exists())transaction.set(document,mediaDocument(media))
    }
    private fun writeSettingsIfNewer(uid:String,settings:AccountSettingsSnapshot)=firestore.runTransaction { transaction ->
        val document=userDocument(uid).collection(SETTINGS).document(MAIN_DOCUMENT)
        val remote=transaction.get(document)
        if((remote.getLong("updatedAtMillis")?:0)<=settings.updatedAtMillis)transaction.set(document,settingsDocument(settings),SetOptions.merge())
    }

    private fun mediaDocument(media: Media): Map<String, Any?> = mapOf(
        "mediaKey" to media.key,
        "tmdbId" to media.id,
        "mediaType" to media.type.routeName,
        "title" to media.title,
        "mediaJson" to media.toJson().toString(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    private fun recentDocument(
        entry: RecentMediaEntry,
        useServerTimestamp: Boolean,
    ): Map<String, Any?> = mediaDocument(entry.media) + mapOf(
        "lastPlayedAt" to if (useServerTimestamp) {
            FieldValue.serverTimestamp()
        } else {
            Timestamp(Date(entry.lastPlayedAtMillis))
        },
        "lastPlayedAtMillis" to entry.lastPlayedAtMillis,
    )

    private fun settingsDocument(settings: AccountSettingsSnapshot): Map<String, Any?> = mapOf(
        "generalPlaybackProvider" to settings.generalProvider,
        "customRamoflixUrl" to settings.ramoflixUrl,
        "customMoviepireUrl" to settings.moviepireUrl,
        "customDorabyUrl" to settings.dorabyUrl,
        "askAliflixEnabled" to settings.askAliflixEnabled,
        "recommendationAiModel" to settings.recommendationAiModel,
        "preferredSubtitleLanguage" to settings.preferredSubtitleLanguage,
        "autoDisplaySubtitles" to settings.autoDisplaySubtitles,
        "updatedAt" to FieldValue.serverTimestamp(),
        "updatedAtMillis" to settings.updatedAtMillis,
    )

    private fun progressDocument(
        progress: PlaybackProgress,
        useServerTimestamp: Boolean,
    ): Map<String, Any?> = mapOf(
        "progressKey" to progress.key,
        "mediaKey" to progress.media.key,
        "mediaJson" to progress.media.toJson().toString(),
        "mediaId" to progress.media.id,
        "mediaType" to progress.media.type.routeName,
        "seasonNumber" to progress.seasonNumber,
        "episodeNumber" to progress.episodeNumber,
        "episodeTitle" to progress.episodeTitle,
        "positionSeconds" to progress.positionSeconds,
        "durationSeconds" to progress.durationSeconds,
        "completed" to progress.completed,
        "completionVersion" to 2,
        "explicitlyRestarted" to progress.explicitlyRestarted,
        "updatedAt" to if (useServerTimestamp) {
            FieldValue.serverTimestamp()
        } else {
            Timestamp(Date(progress.updatedAtMillis))
        },
        "updatedAtMillis" to progress.updatedAtMillis,
    )

    private fun syncErrorMessage(error: Throwable): String = when {
        error is FirebaseFirestoreException &&
            error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Cloud sync does not have permission to access this account's data."
        error is FirebaseFirestoreException &&
            error.code == FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            "Sign in again to resume cloud sync."
        error is FirebaseFirestoreException &&
            error.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            ) -> "Cloud sync is offline. Your changes remain safely on this device."
        else -> "Cloud sync is temporarily unavailable. Your local Aliflix data is unchanged."
    }

    private data class CloudAccountSnapshot(
        val library: LibrarySnapshot,
        val playbackProgress: List<PlaybackProgress>,
        val settings: AccountSettingsSnapshot?,
        val removedMyList:Set<String>,
        val removedFavorites:Set<String>,
        val removedRecent:Map<String,Long>,
        val removedProgress:Map<String,Long>,
    )

    private data class PendingSet(
        val reference: DocumentReference,
        val data: Map<String, Any?>,
        val merge: Boolean = false,
    )

    private companion object {
        const val USERS = "users"
        const val PROFILE = "profile"
        const val MY_LIST = "myList"
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
        const val PROGRESS = "progress"
        const val SETTINGS = "settings"
        const val MAIN_DOCUMENT = "main"
        const val MAX_BATCH_WRITES = 400
        const val INITIAL_SYNC_TIMEOUT_MILLIS = 20_000L
        const val DELETION_TIMEOUT_MILLIS = 60_000L
        const val PROGRESS_CLOUD_INTERVAL_MILLIS = 5_000L
    }
}

private fun QuerySnapshot.toMediaList(): List<Media> = documents.mapNotNull(DocumentSnapshot::toMedia)

private fun QuerySnapshot.tombstones():Map<String,Long> = documents.filter { it.getBoolean("deleted")==true }.associate { it.id to (it.getLong("updatedAtMillis")?:0L) }
private fun QuerySnapshot.toRecentList(): List<RecentMediaEntry> = documents.mapNotNull { document ->
    val media = document.toMedia() ?: return@mapNotNull null
    val timestamp = document.getLong("lastPlayedAtMillis")
        ?: document.getTimestamp("lastPlayedAt")?.toDate()?.time
        ?: 0L
    RecentMediaEntry(media, timestamp)
}.sortedByDescending(RecentMediaEntry::lastPlayedAtMillis)
    .take(AccountMergePolicy.MAX_RECENT)

private fun QuerySnapshot.toPlaybackProgressList(): List<PlaybackProgress> =
    documents.mapNotNull { it.toPlaybackProgress() }.sortedByDescending(PlaybackProgress::updatedAtMillis)

private fun DocumentSnapshot.toPlaybackProgress(): PlaybackProgress? = runCatching {
    if(getBoolean("deleted")==true)return null
    val media = getString("mediaJson")?.let { Media.fromJson(org.json.JSONObject(it)) } ?: return null
    val position = getDouble("positionSeconds") ?: return null
    val duration = getDouble("durationSeconds") ?: return null
    PlaybackProgress(
        key = getString("progressKey") ?: id,
        media = media,
        seasonNumber = getLong("seasonNumber")?.toInt(),
        episodeNumber = getLong("episodeNumber")?.toInt(),
        episodeTitle = getString("episodeTitle"),
        positionSeconds = position,
        durationSeconds = duration,
        updatedAtMillis = getLong("updatedAtMillis") ?: getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
        completed = getBoolean("completed") == true &&
            ((getLong("completionVersion") ?: 0) >= 2 || com.aliflix.app.data.playbackCompleted(position, duration)),
        explicitlyRestarted = getBoolean("explicitlyRestarted") == true,
    ).takeIf { it.key == id && isValidPlaybackProgress(it) }
}.getOrNull()

private fun DocumentSnapshot.toMedia(): Media? {
    if(getBoolean("deleted")==true)return null
    val raw = getString("mediaJson") ?: return null
    val media = runCatching { Media.fromJson(org.json.JSONObject(raw)) }.getOrNull() ?: return null
    return media.takeIf { it.key == id && getString("mediaKey") == it.key }
}

private fun DocumentSnapshot.toSettingsSnapshot(): AccountSettingsSnapshot = AccountSettingsSnapshot(
    generalProvider = getString("generalPlaybackProvider")
        ?: PlaybackProviderId.MOVIEPIRE.name,
    ramoflixUrl = getString("customRamoflixUrl") ?: RamoflixConfig.DEFAULT_URL,
    moviepireUrl = getString("customMoviepireUrl")
        ?: PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
    dorabyUrl = getString("customDorabyUrl")
        ?: PlaybackProviderId.DORABY.defaultBaseUrl,
    askAliflixEnabled = getBoolean("askAliflixEnabled") ?: true,
    recommendationAiModel = getString("recommendationAiModel")
        ?: RecommendationAiModel.GROQ_GPT_OSS_120B.workerValue,
    preferredSubtitleLanguage = getString("preferredSubtitleLanguage") ?: "EN",
    autoDisplaySubtitles = getBoolean("autoDisplaySubtitles") ?: false,
    updatedAtMillis = getLong("updatedAtMillis")
        ?: getTimestamp("updatedAt")?.toDate()?.time
        ?: 0L,
    hasExplicitLocalValues = false,
)
