package com.aliflix.app.account

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
import com.aliflix.app.model.PlaybackProviderId
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
    private val repositoryJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(repositoryJob + Dispatchers.Main.immediate)
    private val snapshotStore = AccountLocalSnapshotStore(context)
    private val _state = MutableStateFlow<AccountSyncState>(AccountSyncState.SignedOut)
    override val state: StateFlow<AccountSyncState> = _state.asStateFlow()
    private val retrySignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val confirmedProgress = mutableMapOf<String, PlaybackProgress>()
    private val deletingUid = MutableStateFlow<String?>(null)
    private val pausedDeletionUid = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            combine(
                accountRepository.state.map { it.uid }.distinctUntilChanged(),
                deletingUid,
            ) { uid, deleting -> uid to deleting }
                .distinctUntilChanged()
                .collectLatest { (uid, deleting) ->
                    switchLocalScope(uid)
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
            pausedDeletionUid.value = null
            deletingUid.value = null
            _state.value = AccountSyncState.SignedOut
        }
    }

    override suspend fun resumeAfterFailedAccountDeletion(uid: String) {
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
                    reconcileInitialState(uid)
                    listeners += attachRemoteListeners(uid)
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

    private suspend fun reconcileInitialState(uid: String) {
        val cloud = withTimeout(INITIAL_SYNC_TIMEOUT_MILLIS) { fetchCloudSnapshot(uid) }
        val mergedLibrary = AccountMergePolicy.mergeLibrary(libraryStore.snapshot(), cloud.library)
        val mergedProgress = mergePlaybackProgress(playbackProgressStore.snapshot(), cloud.playbackProgress)
        val localSettings = currentSettingsSnapshot()
        val mergedSettings = AccountMergePolicy.resolveSettings(localSettings, cloud.settings)
        libraryStore.applySyncedSnapshot(mergedLibrary)
        playbackProgressStore.applySyncedEntries(mergedProgress)
        applySettingsSnapshot(mergedSettings)
        saveActiveLocalSnapshot()

        withTimeout(INITIAL_SYNC_TIMEOUT_MILLIS) {
            uploadMergedSnapshot(uid, mergedLibrary, mergedProgress, mergedSettings)
        }
    }

    private suspend fun fetchCloudSnapshot(uid: String): CloudAccountSnapshot = coroutineScope {
        val user = userDocument(uid)
        val myList = async { user.collection(MY_LIST).get().await().toMediaList() }
        val favorites = async { user.collection(FAVORITES).get().await().toMediaList() }
        val recent = async { user.collection(RECENT).get().await().toRecentList() }
        val playbackProgress = async { user.collection(PROGRESS).get().await().toPlaybackProgressList() }
        val settings = async {
            user.collection(SETTINGS).document(MAIN_DOCUMENT).get().await()
                .takeIf(DocumentSnapshot::exists)
                ?.toSettingsSnapshot()
        }
        CloudAccountSnapshot(
            library = LibrarySnapshot(
                myList = myList.await(),
                favorites = favorites.await(),
                recent = recent.await(),
            ),
            playbackProgress = playbackProgress.await(),
            settings = settings.await(),
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
            writes += PendingSet(user.collection(MY_LIST).document(media.key), mediaDocument(media))
        }
        library.favorites.forEach { media ->
            writes += PendingSet(user.collection(FAVORITES).document(media.key), mediaDocument(media))
        }
        library.recent.forEach { entry ->
            writes += PendingSet(
                user.collection(RECENT).document(entry.media.key),
                recentDocument(entry, useServerTimestamp = false),
            )
        }
        playbackProgress.forEach { progress ->
            writeProgressIfNewer(uid, progress).await()
        }
        writes += PendingSet(
            reference = user.collection(SETTINGS).document(MAIN_DOCUMENT),
            data = settingsDocument(settings),
            merge = true,
        )
        accountRepository.state.value.user?.let { account ->
            writes += PendingSet(
                reference = user.collection(PROFILE).document(MAIN_DOCUMENT),
                data = mapOf(
                    "uid" to account.uid,
                    "displayName" to account.displayName,
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
                    libraryStore.applySyncedSnapshot(libraryStore.snapshot().copy(myList = media))
                }
            },
            user.collection(FAVORITES).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                handleCollectionSnapshot(uid, snapshot, error) { media ->
                    libraryStore.applySyncedSnapshot(libraryStore.snapshot().copy(favorites = media))
                }
            },
            user.collection(RECENT).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!validRemoteSnapshot(uid, snapshot, error)) return@addSnapshotListener
                libraryStore.applySyncedSnapshot(
                    libraryStore.snapshot().copy(recent = snapshot!!.toRecentList()),
                )
                saveActiveLocalSnapshot()
                _state.value = AccountSyncState.Synced
            },
            user.collection(PROGRESS).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (!validRemoteSnapshot(uid, snapshot, error)) return@addSnapshotListener
                playbackProgressStore.applySyncedEntries(
                    mergePlaybackProgress(
                        playbackProgressStore.snapshot(),
                        snapshot!!.toPlaybackProgressList(),
                    ),
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
            val user = userDocument(uid)
            when (mutation) {
                is LibraryMutation.MyListChanged -> {
                    val document = user.collection(MY_LIST).document(mutation.media.key)
                    if (mutation.added) document.set(mediaDocument(mutation.media)) else document.delete()
                }
                is LibraryMutation.FavoriteChanged -> {
                    val document = user.collection(FAVORITES).document(mutation.media.key)
                    if (mutation.added) document.set(mediaDocument(mutation.media)) else document.delete()
                }
                is LibraryMutation.RecentPlayed -> user.collection(RECENT)
                    .document(mutation.entry.media.key)
                    .set(recentDocument(mutation.entry, useServerTimestamp = true))
                is LibraryMutation.RecentRemoved -> user.collection(RECENT)
                    .document(mutation.mediaKey)
                    .delete()
                LibraryMutation.RecentCleared -> clearRemoteRecent(uid)
            }.observeWriteResult(uid)
        }
    }

    private suspend fun collectSettingsMutations(uid: String) {
        merge(playbackRepository.mutations, recommendationStore.mutations).collect {
            if (accountRepository.uid != uid) return@collect
            val settings = currentSettingsSnapshot()
            saveActiveLocalSnapshot()
            userDocument(uid).collection(SETTINGS).document(MAIN_DOCUMENT)
                .set(settingsDocument(settings), SetOptions.merge())
                .observeWriteResult(uid)
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
            val remote = transaction.get(document).toPlaybackProgress()
            val winner = mergePlaybackProgress(listOf(progress), listOfNotNull(remote)).firstOrNull()
            if (winner == progress && remote != progress) {
                transaction.set(document, progressDocument(progress, useServerTimestamp = false))
                true
            } else false
        }.addOnSuccessListener {
            if (accountRepository.uid == uid) confirmedProgress[cacheKey] = progress
        }
    }

    private fun clearRemoteRecent(uid: String): Task<*> = userDocument(uid).collection(RECENT).get()
        .addOnSuccessListener { snapshot ->
            val batch = firestore.batch()
            snapshot.documents.forEach { batch.delete(it.reference) }
            batch.commit().observeWriteResult(uid)
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
            generalProvider = playback.safeGeneralProvider.name,
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
            generalProvider = PlaybackProviderId.fromStoredValue(settings.generalProvider)
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
        const val PROGRESS_CLOUD_INTERVAL_MILLIS = 45_000L
    }
}

private fun QuerySnapshot.toMediaList(): List<Media> = documents.mapNotNull(DocumentSnapshot::toMedia)

private fun QuerySnapshot.toRecentList(): List<RecentMediaEntry> = documents.mapNotNull { document ->
    val media = document.toMedia() ?: return@mapNotNull null
    val timestamp = document.getTimestamp("lastPlayedAt")?.toDate()?.time
        ?: document.getLong("lastPlayedAtMillis")
        ?: 0L
    RecentMediaEntry(media, timestamp)
}.sortedByDescending(RecentMediaEntry::lastPlayedAtMillis)
    .take(AccountMergePolicy.MAX_RECENT)

private fun QuerySnapshot.toPlaybackProgressList(): List<PlaybackProgress> =
    documents.mapNotNull { it.toPlaybackProgress() }.sortedByDescending(PlaybackProgress::updatedAtMillis)

private fun DocumentSnapshot.toPlaybackProgress(): PlaybackProgress? = runCatching {
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
        ?: RecommendationAiModel.GROQ_QWEN_3_8_27B.workerValue,
    preferredSubtitleLanguage = getString("preferredSubtitleLanguage") ?: "EN",
    autoDisplaySubtitles = getBoolean("autoDisplaySubtitles") ?: false,
    updatedAtMillis = getTimestamp("updatedAt")?.toDate()?.time
        ?: getLong("updatedAtMillis")
        ?: 0L,
    hasExplicitLocalValues = false,
)
