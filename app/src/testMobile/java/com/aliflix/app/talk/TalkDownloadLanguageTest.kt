package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkDownloadLanguageTest {
 @Test fun toolBcp47CodesAndNativeUppercasePreferencesResolveToTheSameActualLanguage(){
  assertEquals("ar",talkDownloadSubtitleLanguage("ar"))
  assertEquals("ar",talkDownloadSubtitleLanguage("AR"))
  assertEquals("de",talkDownloadSubtitleLanguage("DE"))
  assertEquals("",talkDownloadSubtitleLanguage("off"))
  assertTrue(runCatching{talkDownloadSubtitleLanguage("invented")}.isFailure)
 }
}
