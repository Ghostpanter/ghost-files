package com.ghostpanter.files.core.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStateTest {
    @Test fun happyPath() {
        var s = PairState()
        s = reducePair(s, PairEvent.StartWaitCode)
        assertEquals(PairPhase.WAITING_CODE, s.phase)
        s = reducePair(s, PairEvent.CodeEntered("123456"))
        assertEquals(PairPhase.PAIRING, s.phase)
        s = reducePair(s, PairEvent.PairSucceeded)
        assertEquals(PairPhase.CONNECTING, s.phase)
        s = reducePair(s, PairEvent.ConnectPort(37123))
        assertEquals(37123, s.connectPort)
        s = reducePair(s, PairEvent.Connected)
        assertEquals(PairPhase.STARTING_SERVER, s.phase)
        s = reducePair(s, PairEvent.ServerStarted)
        s = reducePair(s, PairEvent.BinderReady)
        assertEquals(PairPhase.READY, s.phase)
    }

    @Test fun rejectsBadCode() {
        var s = reducePair(PairState(), PairEvent.StartWaitCode)
        s = reducePair(s, PairEvent.CodeEntered("12"))
        assertEquals(PairPhase.FAILED, s.phase)
        s = reducePair(PairState(PairPhase.WAITING_CODE), PairEvent.CodeEntered("abcdef"))
        assertEquals(PairPhase.FAILED, s.phase)
    }

    @Test fun failAndReset() {
        var s = reducePair(PairState(PairPhase.PAIRING), PairEvent.Fail("timeout"))
        assertEquals(PairPhase.FAILED, s.phase)
        assertEquals("timeout", s.detail)
        s = reducePair(s, PairEvent.Reset)
        assertEquals(PairPhase.IDLE, s.phase)
    }

    @Test fun callingUidGuard() {
        assertTrue(acceptCallingUid(10123, 10123))
        assertTrue(acceptCallingUid(0, 10123)) // root
        assertFalse(acceptCallingUid(10124, 10123))
        assertFalse(acceptCallingUid(2000, 10123)) // shell must not impersonate app calls into our service
    }

    @Test fun binderSenderUidGuard() {
        assertTrue(acceptBinderSenderUid(2000))
        assertTrue(acceptBinderSenderUid(0))
        assertFalse(acceptBinderSenderUid(10123))
    }
}
