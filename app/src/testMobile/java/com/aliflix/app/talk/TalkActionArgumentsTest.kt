package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkActionArgumentsTest {
    @Test fun specialsAreExactAndOmittedSeasonIsTheOnlyWildcard(){
        val special=TalkEpisodeArguments.from(JSONObject().put("season",0).put("episode",1))
        assertTrue(special.matches(0,1));assertFalse(special.matches(1,1))
        assertTrue(TalkEpisodeArguments.from(JSONObject().put("episode",1)).matches(7,1))
    }
    @Test fun malformedEpisodesCannotReachNativeMutations(){
        for(value in listOf<Any>(-1,1.2,1e100,"1",true))assertThrows(IllegalArgumentException::class.java){TalkEpisodeArguments.from(JSONObject().put("season",value))}
        for(value in listOf<Any>(0,-1,1.2,1e100,"1"))assertThrows(IllegalArgumentException::class.java){TalkEpisodeArguments.from(JSONObject().put("episode",value))}
        TalkEpisodeArguments.from(JSONObject().put("season",0).put("episode",1)).requireKind(MediaType.TV)
        assertThrows(IllegalArgumentException::class.java){TalkEpisodeArguments.from(JSONObject().put("episode",1)).requireKind(MediaType.MOVIE)}
    }
    @Test fun numericOverflowAndUnsupportedSettingsDoNotBecomeClampedSuccess(){
        for(value in listOf<Any>(1e100,-.1,1.1,"0.5"))assertThrows(IllegalArgumentException::class.java){JSONObject().put("numberValue",value).talkSettingNumber(0f..1f)}
        assertEquals(.5f,JSONObject().put("numberValue",.5).talkSettingNumber(0f..1f))
    }
}
