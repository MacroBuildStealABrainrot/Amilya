package com.woundcopilot.android
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Invoke these methods separately around the external install/force-stop/reboot steps. */
class UpgradeStorageTest{
    @Test fun prepareMarker(){
        val vault=Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("Do not overwrite an existing marker",vault.read("upgrade-proof")==null)
        vault.write("upgrade-proof","SYNTHETIC upgrade persistence 2026-09-08")
        assertEquals("SYNTHETIC upgrade persistence 2026-09-08",vault.read("upgrade-proof"))
    }
    @Test fun verifyMarker(){
        val vault=Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("SYNTHETIC upgrade persistence 2026-09-08",vault.read("upgrade-proof"))
    }
    @Test fun removeMarker(){Vault(InstrumentationRegistry.getInstrumentation().targetContext).remove("upgrade-proof")}
}
