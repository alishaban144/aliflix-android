package com.aliflix.app.player

import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class PrioritySourcePolicyTest {
    @Test fun networkParsingAndHostFailuresCannotCertifyAbsenceOnAnyPrioritySite() {
        for(provider in listOf(MobilePlaybackProvider.SERIENSTREAM,MobilePlaybackProvider.FILMO,MobilePlaybackProvider.ANIWORLD)) {
            for(error in listOf(java.net.SocketTimeoutException(),java.io.IOException(),IllegalArgumentException("Missing site metadata"),NoNativeServersException()))
                assertFalse(provider.name,mayAutomaticallyLeaveSource(provider,error))
            assertTrue(mayAutomaticallyLeaveSource(provider,ProviderItemUnavailable(provider)))
            assertFalse(mayAutomaticallyLeaveSource(provider,ProviderItemUnavailable(MobilePlaybackProvider.FLIXER)))
        }
        assertTrue(mayAutomaticallyLeaveSource(MobilePlaybackProvider.FLIXER,java.io.IOException()))
    }
}
