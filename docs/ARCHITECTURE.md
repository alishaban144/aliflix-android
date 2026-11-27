# Aliflix code map

Most Android Kotlin lives under `app/src/{main,mobile}/java/com/aliflix/app/`. Both source sets compile into the phone app. The retained `mobile` flavor keeps the published Gradle tasks, APK paths, native binaries, and validation scripts compatible.

| Task | Start with |
| --- | --- |
| Launch, navigation, and cross-screen state | [MainActivity](../app/src/main/java/com/aliflix/app/MainActivity.kt), [AliflixApp](../app/src/main/java/com/aliflix/app/ui/AliflixApp.kt), [AliflixViewModel](../app/src/main/java/com/aliflix/app/AliflixViewModel.kt) |
| Home, hero, and shelves | [HomeScreen](../app/src/main/java/com/aliflix/app/ui/HomeScreen.kt), `data/HomeSnapshotStore.kt` |
| Search, category filters, and Ask | [DiscoverScreen](../app/src/main/java/com/aliflix/app/ui/discover/DiscoverScreen.kt), `ui/discover/DiscoverCatalogue.kt`, `ui/discover/UnifiedAskAliflix.kt` |
| Details, cast, episodes, and ratings | [DetailScreen](../app/src/main/java/com/aliflix/app/ui/DetailScreen.kt), [BrowseScreens](../app/src/main/java/com/aliflix/app/ui/BrowseScreens.kt), `data/CatalogClient.kt`, `data/omdb/` |
| My Space, history, settings, and updater | [MySpaceScreen](../app/src/main/java/com/aliflix/app/ui/MySpaceScreen.kt), `ui/MobileSettingsScreen.kt`, `data/LibraryStore.kt`, `update/AppUpdateManager.kt` |
| Accounts and Firestore sync | `main/account/AccountRepository.kt`, [FirebaseAccountSyncRepository](../app/src/mobile/java/com/aliflix/app/account/FirebaseAccountSyncRepository.kt), [Firestore rules](../firestore.rules) |
| Player, downloads, and Cast | See Playback below. Downloads live in [mobile/downloads](../app/src/mobile/java/com/aliflix/app/downloads/) and Cast in the [mobile player package](../app/src/mobile/java/com/aliflix/app/player/). |
| Talk conversation and player scene context | [TalkVoiceActivity](../app/src/mobile/java/com/aliflix/app/talk/TalkVoiceActivity.kt), `TalkVoiceSession.kt`, `TalkSceneContext.kt`, `mobile/player/PlayerSceneMemory.kt` |
| Worker endpoints, provider routing, and schemas | [src/index.ts](../recommendation-worker/src/index.ts), `src/askResearch.ts`, `src/talk.ts`, `src/talkProviders.ts`, `src/schemas.ts`, [wrangler.jsonc](../recommendation-worker/wrangler.jsonc) |

## Playback

1. `AliflixApp` dispatches a `PlaybackSelection` under [PlaybackNavigationPolicy](../app/src/main/java/com/aliflix/app/player/PlaybackNavigationPolicy.kt).
2. Native playback enters [NativePlayerActivity](../app/src/mobile/java/com/aliflix/app/player/NativePlayerActivity.kt). [NativeStreamResolver](../app/src/mobile/java/com/aliflix/app/player/NativeStreamResolver.kt) resolves provider streams. [NativePlaybackService](../app/src/mobile/java/com/aliflix/app/player/NativePlaybackService.kt) owns the Media3 session.
3. [WebPlayerController](../app/src/main/java/com/aliflix/app/player/WebPlayerController.kt) runs headless provider resolution and drives the visible fallback in [WebPlayerScreen](../app/src/main/java/com/aliflix/app/player/WebPlayerScreen.kt). [MoviepireWebScripts](../app/src/main/java/com/aliflix/app/player/MoviepireWebScripts.kt) holds that provider's WebView scripts. Both route families share selection, progress, and subtitle primitives. Use the existing compatibility and fallback tests before changing provider dispatch.

## Code and contracts across boundaries

- When changing Worker payloads, check the Android caller in `main/recommendation/`, `main/data/`, or `mobile/talk/` and the Worker schema and test for that endpoint.
- Keep media IDs and persisted library, playback, provider, and account keys stable across releases. Use existing migrations for older state.
- Android JVM tests live in `app/src/test` and `app/src/testMobile`. Device tests live in `app/src/androidTest` and `app/src/androidTestMobile`. Worker tests live in `recommendation-worker/test`.
