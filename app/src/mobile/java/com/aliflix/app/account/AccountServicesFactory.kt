package com.aliflix.app.account

import android.app.Application
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.recommendation.RecommendationStore
import kotlinx.coroutines.CoroutineScope

fun createAccountServices(
    application: Application,
    libraryStore: LibraryStore,
    playbackRepository: PlaybackProviderRepository,
    recommendationStore: RecommendationStore,
    scope: CoroutineScope,
): AccountServices {
    val accountRepository = FirebaseAccountRepository(application)
    return AccountServices(
        accountRepository = accountRepository,
        syncRepository = FirebaseAccountSyncRepository(
            context = application,
            accountRepository = accountRepository,
            libraryStore = libraryStore,
            playbackRepository = playbackRepository,
            recommendationStore = recommendationStore,
            parentScope = scope,
        ),
    )
}
