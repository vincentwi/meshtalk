package com.meshtalk.app.vox
import org.junit.Assert.*
import org.junit.Test

class VoxStateMachineTest {
    @Test fun startsIdle() { val vox = VoxStateMachine(); assertEquals(VoxState.IDLE, vox.state); assertFalse(vox.shouldTransmit) }
    @Test fun transitionsToSpeakingAfterOnset() { val vox = VoxStateMachine(onsetMs = 100, hangoverMs = 500); repeat(5) { vox.onVadResult(0.8f, 32) }; assertEquals(VoxState.SPEAKING, vox.state); assertTrue(vox.shouldTransmit) }
    @Test fun staysIdleDuringBriefSpeech() { val vox = VoxStateMachine(onsetMs = 200, hangoverMs = 500); repeat(2) { vox.onVadResult(0.8f, 32) }; assertEquals(VoxState.IDLE, vox.state) }
    @Test fun transitionsToHangoverOnSilence() { val vox = VoxStateMachine(onsetMs = 50, hangoverMs = 500); repeat(3) { vox.onVadResult(0.8f, 32) }; assertEquals(VoxState.SPEAKING, vox.state); vox.onVadResult(0.1f, 32); assertEquals(VoxState.HANGOVER, vox.state); assertTrue(vox.shouldTransmit) }
    @Test fun returnsToIdleAfterHangover() { val vox = VoxStateMachine(onsetMs = 50, hangoverMs = 100); repeat(3) { vox.onVadResult(0.8f, 32) }; repeat(5) { vox.onVadResult(0.1f, 32) }; assertEquals(VoxState.IDLE, vox.state); assertFalse(vox.shouldTransmit) }
    @Test fun resumesSpeakingFromHangover() { val vox = VoxStateMachine(onsetMs = 50, hangoverMs = 500); repeat(3) { vox.onVadResult(0.8f, 32) }; vox.onVadResult(0.1f, 32); assertEquals(VoxState.HANGOVER, vox.state); vox.onVadResult(0.8f, 32); assertEquals(VoxState.SPEAKING, vox.state) }
    @Test fun mutePreventsTransmission() { val vox = VoxStateMachine(onsetMs = 50, hangoverMs = 500); vox.muted = true; repeat(10) { vox.onVadResult(0.9f, 32) }; assertEquals(VoxState.IDLE, vox.state); assertFalse(vox.shouldTransmit) }
}
