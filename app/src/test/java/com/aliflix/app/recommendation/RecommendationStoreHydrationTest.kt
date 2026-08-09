package com.aliflix.app.recommendation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationStoreHydrationTest {
    @Test
    fun feedbackBeforeHydrationMergesWithPersistedTaste() = runTest {
        val context = InMemoryContext(
            initial = mapOf(
                "enabled" to false,
                "taste_signals" to
                    """[{"key":"genre:drama","positive":3,"negative":0,"updatedAt":1}]""",
                "seen_keys" to """["movie:7"]""",
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecommendationStore(
            context = context,
            scope = this,
            dispatchers = RecommendationDispatchers(dispatcher, dispatcher),
        )

        assertFalse(store.enabled.value)
        store.recordAccepted(
            Media(
                id = 8,
                type = MediaType.MOVIE,
                title = "New feedback",
                year = "2024",
                genres = listOf("Thriller"),
            ),
        )

        advanceUntilIdle()

        val taste = store.taste.value
        assertEquals(3, taste.signals.getValue("genre:drama").positiveObservations)
        assertEquals(1, taste.signals.getValue("genre:thriller").positiveObservations)
        assertEquals(1, taste.signals.getValue("type:movie").positiveObservations)
        assertTrue("movie:7" in taste.seenKeys)
        assertFalse(store.enabled.value)
    }

    @Test
    fun enabledFailsClosedUntilPersistedOptInLoads() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecommendationStore(
            context = InMemoryContext(mapOf("enabled" to true)),
            scope = this,
            dispatchers = RecommendationDispatchers(dispatcher, dispatcher),
        )

        assertFalse(store.enabled.value)
        advanceUntilIdle()
        assertTrue(store.enabled.value)
    }

    @Test
    fun seenHistoryCapKeepsNewestFeedback() {
        val store = RecommendationStore(InMemoryContext())

        repeat(260) { id ->
            store.markSeen(Media(id = id, type = MediaType.MOVIE, title = "Movie $id"))
        }

        assertEquals(250, store.taste.value.seenKeys.size)
        assertFalse("movie:0" in store.taste.value.seenKeys)
        assertTrue("movie:259" in store.taste.value.seenKeys)
    }

    private class InMemoryContext(
        initial: Map<String, Any?> = emptyMap(),
    ) : ContextWrapper(null) {
        private val preferences = InMemoryPreferences(initial).value

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = preferences
    }

    private class InMemoryPreferences(initial: Map<String, Any?>) : InvocationHandler {
        private val values = linkedMapOf<String, Any?>().apply { putAll(initial) }
        val value: SharedPreferences
        private val editor: SharedPreferences.Editor

        init {
            value = proxy(SharedPreferences::class.java, this)
            editor = proxy(SharedPreferences.Editor::class.java, EditorHandler(values))
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[arguments[0]] as? String ?: arguments[1]
                "getStringSet" ->
                    @Suppress("UNCHECKED_CAST")
                    ((values[arguments[0]] as? Set<String>) ?: arguments[1])
                "getInt" -> values[arguments[0]] as? Int ?: arguments[1]
                "getLong" -> values[arguments[0]] as? Long ?: arguments[1]
                "getFloat" -> values[arguments[0]] as? Float ?: arguments[1]
                "getBoolean" -> values[arguments[0]] as? Boolean ?: arguments[1]
                "contains" -> values.containsKey(arguments[0])
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> Unit
                "toString" -> "InMemorySharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private class EditorHandler(
        private val values: MutableMap<String, Any?>,
    ) : InvocationHandler {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clear = false

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (method.name) {
                "putString",
                "putStringSet",
                "putInt",
                "putLong",
                "putFloat",
                "putBoolean",
                -> {
                    val key = arguments[0] as String
                    pending[key] = arguments[1]
                    removals -= key
                    proxy
                }
                "remove" -> {
                    val key = arguments[0] as String
                    removals += key
                    pending -= key
                    proxy
                }
                "clear" -> {
                    clear = true
                    proxy
                }
                "commit" -> {
                    applyChanges()
                    true
                }
                "apply" -> {
                    applyChanges()
                    Unit
                }
                "toString" -> "InMemorySharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }

        private fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
            clear = false
            removals.clear()
            pending.clear()
        }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

        fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
