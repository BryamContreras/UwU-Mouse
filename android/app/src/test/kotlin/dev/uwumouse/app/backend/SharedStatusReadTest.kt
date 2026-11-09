package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class SharedStatusReadTest {
    private class Harness {
        val work=ArrayDeque<Runnable>()
        val main=ArrayDeque<Runnable>()
        var reads=0
        var fail=false
        val reader=SharedStatusRead<Any,Int>({work.add(it)},{
            reads++
            if(fail) error("backend unavailable")
            reads
        },{main.add(it)})
        fun deliver() { while(main.isNotEmpty()) main.removeFirst().run() }
    }
    @Test fun concurrentScreensShareOneReadAndReceiveResultsOnlyOnTheDeliveryThread() {
        val harness=Harness();val backend=Any();val values=mutableListOf<Int>()
        repeat(100) { harness.reader.request(backend) { values.add(it.getOrThrow()) } }
        assertEquals(1,harness.work.size);assertEquals(0,harness.reads)
        harness.work.removeFirst().run()
        assertTrue(values.isEmpty())
        harness.deliver()
        assertEquals(1,harness.reads);assertEquals(List(100){1},values)
    }
    @Test fun repeatedRecreationCancelsSubscriptionsWithoutAddingBlockedReads() {
        val harness=Harness();val backend=Any();var oldReplies=0;var currentReplies=0
        repeat(1000) {
            requireNotNull(harness.reader.request(backend) {oldReplies++}).cancel()
        }
        harness.reader.request(backend) {currentReplies++}
        assertEquals(1,harness.work.size)
        harness.work.removeFirst().run();harness.deliver()
        assertEquals(0,oldReplies);assertEquals(1,currentReplies);assertEquals(1,harness.reads)
    }
    @Test fun destroyAfterReadBeforeMainDeliverySuppressesTheLateReply() {
        val harness=Harness();var replies=0
        val subscription=requireNotNull(harness.reader.request(Any()) {replies++})
        harness.work.removeFirst().run()
        subscription.cancel();harness.deliver()
        assertEquals(0,replies)
    }
    @Test fun anotherBackendCannotQueueBehindABlockedOldOne() {
        val harness=Harness();val first=Any();val second=Any()
        harness.reader.request(first) {}
        assertNull(harness.reader.request(second) {fail("unexpected reply")})
        assertEquals(1,harness.work.size)
        harness.work.removeFirst().run();harness.deliver()
        assertNotNull(harness.reader.request(second) {})
        assertEquals(1,harness.work.size)
    }
    @Test fun failedReadCompletesWaitersAndDoesNotPoisonTheNextRead() {
        val harness=Harness();val backend=Any();val outcomes=mutableListOf<Result<Int>>()
        harness.fail=true;harness.reader.request(backend) {outcomes.add(it)}
        harness.work.removeFirst().run();harness.deliver()
        assertTrue(outcomes.single().isFailure)
        harness.fail=false;harness.reader.request(backend) {outcomes.add(it)}
        harness.work.removeFirst().run();harness.deliver()
        assertEquals(2,outcomes.last().getOrThrow())
    }
    @Test fun aNewGenerationJoiningAnOldFlightCannotAcceptItsOlderSnapshot() {
        val work=ArrayDeque<Runnable>();val main=ArrayDeque<Runnable>();val backend=Any()
        var generation=1;var reads=0;var accepted=0;var stale=0
        lateinit var reader:SharedStatusRead<Any,StatusSnapshot<Int,Int>>
        fun listener(requested:Int):(Result<StatusSnapshot<Int,Int>>)->Unit = { result ->
            if(result.getOrThrow().isCurrent(requested,generation)) accepted++ else stale++
        }
        reader=SharedStatusRead({work.add(it)},{
            val atStart=generation
            reads++
            if(reads==1) {
                generation=2 // Stop/recreate happens while the first Binder read is outstanding.
                reader.request(backend,listener(generation))
            }
            StatusSnapshot(atStart,reads)
        },{main.add(it)})
        reader.request(backend,listener(generation))
        work.removeFirst().run()
        while(main.isNotEmpty()) main.removeFirst().run()
        assertEquals(1,reads);assertEquals(0,accepted);assertEquals(2,stale)
        reader.request(backend,listener(generation))
        work.removeFirst().run()
        while(main.isNotEmpty()) main.removeFirst().run()
        assertEquals(2,reads);assertEquals(1,accepted)
    }
}
