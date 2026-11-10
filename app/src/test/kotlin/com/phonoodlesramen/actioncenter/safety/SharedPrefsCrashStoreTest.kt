package com.phonoodlesramen.actioncenter.safety

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPrefsCrashStoreTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("crash-guard-test", Context.MODE_PRIVATE)
    private val store = SharedPrefsCrashStore(prefs)

    @Test
    fun `an empty store has no crash`() {
        assertNull(store.read())
    }

    @Test
    fun `a recorded crash is read back by a new store`() {
        store.write(CrashRecord(42L, "IllegalStateException: boom"))

        assertEquals(CrashRecord(42L, "IllegalStateException: boom"), SharedPrefsCrashStore(prefs).read())
    }

    @Test
    fun `clear removes the crash`() {
        store.write(CrashRecord(42L, "boom"))

        store.clear()

        assertNull(SharedPrefsCrashStore(prefs).read())
    }
}
