package com.aliflix.app.account

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixViewModel
import com.aliflix.app.MobileAliflixApplication
import org.junit.Assert.*
import org.junit.Test

class MobileSyncLifetimeTest {
    @Test fun startupAndRecreatedScreensShareTheSameAccountAndStores() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as MobileAliflixApplication
        instrumentation.runOnMainSync {
            val first=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
            val second=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
            val factory=ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            val a=ViewModelProvider(first,factory)[AliflixViewModel::class.java]
            val services=app.accountServices
            assertSame(services.accountRepository.state,a.accountState)
            assertSame(app.playbackProgressStore,a.playbackProgressStore)
            first.viewModelStore.clear()
            val b=ViewModelProvider(second,factory)[AliflixViewModel::class.java]
            assertSame(services.accountRepository.state,b.accountState)
            assertSame(a.accountSyncState,b.accountSyncState)
            assertSame(app.playbackProgressStore,b.playbackProgressStore)
            second.viewModelStore.clear()
        }
    }
}
