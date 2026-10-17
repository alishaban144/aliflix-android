package com.aliflix.app.player
import com.aliflix.app.model.MobilePlaybackProvider
import org.junit.Test
import org.junit.Assert.*
class PrioritySourcePolicyTest {
 @Test fun failuresAllowBoundedAutomaticFallbackAfterPrimaryMirrors(){
  for(provider in listOf(MobilePlaybackProvider.FILMO,MobilePlaybackProvider.SERIENSTREAM,MobilePlaybackProvider.ANIWORLD))
   for(error in listOf(java.io.IOException("network"),IllegalStateException("catalogue"),ProviderItemUnavailable(provider)))
    assertTrue(mayAutomaticallyLeaveSource(provider,error))
 }
}
