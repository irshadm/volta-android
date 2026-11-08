package dev.irshadm.volta.core.ismart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Readings and replies that differ between the Windsor and other MG cars. */
class CarDifferencesTest {
    @Test fun `Windsor tyres count in 0_2 psi steps`() {
        assertEquals(listOf(33.8, 35.4, 34.0, 33.0), TyreScale.psi(listOf(169, 177, 170, 165)))
    }

    @Test fun `ZS EV tyres count in 0_04 bar steps`() {
        // A ZS EV owner saw 14 psi on every wheel with the Windsor's scale; the car's own gauge said about 40.
        assertEquals(listOf(40.6, 40.6, 41.8, 41.8), TyreScale.psi(listOf(70, 70, 72, 72)))
    }

    @Test fun `a flat tyre on a SAIC car stays on the bar scale`() {
        assertEquals(listOf(40.6, 7.0, 40.6, 40.6), TyreScale.psi(listOf(70, 12, 70, 70)))
    }

    @Test fun `a soft Windsor tyre stays on the psi scale`() {
        assertEquals(listOf(33.8, 20.0, 33.8, 33.8), TyreScale.psi(listOf(169, 100, 169, 169)))
    }

    @Test fun `out-of-range tyre readings are dropped`() {
        assertEquals(listOf(null, 33.8, null, 33.8), TyreScale.psi(listOf(0, 169, null, 169)))
    }

    @Test fun `MG's multi-factor rejection is recognised`() {
        assertTrue(mfaRequired("Multi-factor authentication required"))
        assertTrue(mfaRequired("multifactor login is enabled"))
        assertTrue(mfaRequired("MFA required"))
        assertFalse(mfaRequired("Incorrect mobile number or password."))
    }
}
