package com.aliflix.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class HomePicksStabilityDeviceTest {
    @Test fun detailsAndBackPreserveTheCurrentPickedRail() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveHomePicks")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity:MainActivity?=null
        runBlocking { withTimeout(20000) { while(activity==null) {
            instrumentation.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
            delay(50)
        } } }
        lateinit var vm:AliflixViewModel
        instrumentation.runOnMainSync { vm=ViewModelProvider(requireNotNull(activity))[AliflixViewModel::class.java] }
        try {
            runBlocking { withTimeout(45000) { while(vm.pickedForYou.value.isEmpty())delay(100) } }
            val before=vm.pickedForYou.value.map { it.key }
            instrumentation.runOnMainSync { vm.openDetails(vm.pickedForYou.value.first()) }
            runBlocking { delay(1500) }
            instrumentation.runOnMainSync { vm.closeDetails() }
            runBlocking { delay(1500) }
            assertEquals(before,vm.pickedForYou.value.take(before.size).map { it.key })
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
