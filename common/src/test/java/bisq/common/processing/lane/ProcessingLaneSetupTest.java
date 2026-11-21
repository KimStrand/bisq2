/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.processing.lane;

import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The required setup of an entity: its place between the first save and the first input, and its failures.
 */
class ProcessingLaneSetupTest extends ProcessingLaneCounterFixture {
    @Test
    void setupRunsAfterTheInitialSaveAndBeforeTheFirstInputWithoutSavingASetupFlag() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var setupAttempts = new AtomicInteger();
        var first = new Message("first");
        // The router submits pending messages right behind the initializer.
        var initialized = lane.submit(new Initialize(first));
        var waiting = lane.submit(new Message("waiting"));
        fixture.onSetup = snapshot -> {
            setupAttempts.incrementAndGet();
            assertEquals(new Counter(0, false), snapshot);
            assertEquals(snapshot, fixture.lastSaved().snapshot());
            assertEquals(List.of(first), fixture.lastSaved().pendingInputs());
            assertTrue(fixture.attempted.isEmpty());
            assertFalse(waiting.isDone());
        };
        fixture.handler = (snapshot, work) -> {
            assertEquals(1, setupAttempts.get());
            if (work.input().equals(first)) {
                assertEquals(1, fixture.saved.size());
            }
            return applied(snapshot, emptyOutput());
        };
        assertEquals(0, setupAttempts.get());
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(List.of(first, new Message("waiting")), fixture.attemptedInputs());
        assertEquals(List.of(new Counter(0, false), new Counter(1, false), new Counter(1, false), new Counter(2, false)),
                fixture.saved.stream().map(Checkpoint::snapshot).toList());
        assertEquals(List.of(new Message("waiting")),
                fixture.saved.get(2).pendingInputs());

        var later = lane.submit(new Action("later"));
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), awaitSnapshot(later));
        assertEquals(1, setupAttempts.get());
    }

    @Test
    void failedSetupRetainsPreparationAndTheFirstInputUntilExplicitRetry() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        var setupFailure = new IllegalStateException("Chat could not be saved");
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> {
            if (setupAttempts.incrementAndGet() <= 2) {
                throw setupFailure;
            }
        };
        var first = new Message("first");
        // The router submits pending messages right behind the initializer. Each queued attempt retries setup.
        var initialized = lane.submit(new Initialize(first));
        var waiting = lane.submit(new Message("waiting"));
        fixture.executor.runAll();

        assertSame(setupFailure, failure(initialized));
        var initializedPending = assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        assertEquals(CoordinationPhase.SETUP, initializedPending.phase());
        var waitingPending = assertInstanceOf(SubmissionResult.Pending.class, await(waiting));
        assertEquals(CoordinationPhase.SETUP, waitingPending.phase());
        assertInstanceOf(ProcessingSummary.Deferred.class, waitingPending.processingSummary());
        assertFalse(lane.isClosed());
        assertEquals(2, fixture.saved.size());
        assertEquals(List.of(first, new Message("waiting")), fixture.lastSaved().pendingInputs());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(1, preparations.get());
        assertEquals(2, setupAttempts.get());

        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(retry));
        assertEquals(List.of(first, new Message("waiting")), fixture.attemptedInputs());
        assertEquals(4, fixture.saved.size());
        assertEquals(List.of(new Message("waiting")),
                fixture.saved.get(2).pendingInputs());
        assertEquals(1, preparations.get());
        assertEquals(3, setupAttempts.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void setupFailureSurvivesIgnoredInputsAndClearsWithoutADomainTransition(boolean restored) throws Exception {
        var fixture = new Fixture();
        var snapshot = new Counter(0, false);
        var checkpoint = new Checkpoint<Counter, CounterInput>(snapshot, List.of(), List.of(), List.of());
        var lane = restored ? fixture.restoreLane(checkpoint, fixture.executor) : fixture.createLane(fixture.executor, true);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = current -> { throw setupFailure; };

        var started = restored ? lane.resume() : lane.submit(new Initialize(new Message("first")));
        var observedAtCompletion = started.handle((ignored, failure) -> fixture.published.getLast());
        fixture.executor.runAll();

        assertSame(setupFailure, failure(started));
        var failedPublication = new PublishedSnapshot(snapshot, Optional.empty(), Optional.of(setupFailure));
        assertEquals(failedPublication, await(observedAtCompletion));
        assertFalse(lane.isClosed());

        fixture.routes = (current, input) -> List.of();
        var ignored = lane.submit(new Message("irrelevant"));
        fixture.executor.runAll();
        var waiting = assertInstanceOf(SubmissionResult.Pending.class, await(ignored));
        assertEquals(CoordinationPhase.SETUP, waiting.phase());
        assertInstanceOf(ProcessingSummary.Deferred.class, waiting.processingSummary());
        assertEquals(restored ? List.of(new Message("irrelevant")) : List.of(new Message("first"), new Message("irrelevant")),
                fixture.lastSaved().pendingInputs());
        assertEquals(failedPublication, fixture.published.getLast());

        fixture.onSetup = current -> {};
        var resumed = lane.resume();
        var observedAfterRetry = resumed.thenApply(current -> fixture.published.getLast());
        fixture.executor.runAll();

        assertEquals(snapshot, await(resumed));
        assertEquals(new PublishedSnapshot(snapshot, Optional.empty(), Optional.empty()), await(observedAfterRetry));
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void persistenceAndSetupFailuresRemainIndependentUntilTheirOwnOperationSucceeds() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var snapshot = new Counter(0, false);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = current -> { throw setupFailure; };
        var started = lane.submit(new Initialize(new Message("first")));
        fixture.executor.runAll();
        assertSame(setupFailure, failure(started));

        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var incoming = lane.submit(new Message("retained"));
        fixture.executor.runAll();

        assertSame(saveFailure, failure(incoming));
        assertEquals(new PublishedSnapshot(snapshot, Optional.of(saveFailure), Optional.of(setupFailure)),
                fixture.published.getLast());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        var saved = lane.resume();
        fixture.executor.runAll();

        assertSame(setupFailure, failure(saved));
        assertEquals(new PublishedSnapshot(snapshot, Optional.empty(), Optional.of(setupFailure)),
                fixture.published.getLast());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSetup = current -> {};
        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), await(resumed));
        assertEquals(new PublishedSnapshot(new Counter(2, false), Optional.empty(), Optional.empty()),
                fixture.published.getLast());
        assertEquals(List.of(new Message("first"), new Message("retained")), fixture.attemptedInputs());
    }

    @Test
    void successfulSetupIsNotRepeatedAfterTheFirstInputCheckpointFails() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        var saveFailure = new IllegalStateException("Protocol checkpoint could not be saved");
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> setupAttempts.incrementAndGet();
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() == 1) {
                throw saveFailure;
            }
        };
        var initialized = lane.submit(new Initialize(new Action("increment")));
        fixture.executor.runAll();

        assertSame(saveFailure, failure(initialized));
        assertEquals(1, fixture.saved.size());
        assertEquals(1, setupAttempts.get());
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(new Action("increment")), fixture.lastSaved().pendingInputs());
        assertEquals(List.of(new Action("increment")), fixture.attemptedInputs());
        assertFalse(lane.isClosed());

        var blocked = lane.resume();
        fixture.executor.runAll();
        assertSame(saveFailure, failure(blocked));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, setupAttempts.get());

        fixture.onSave = checkpoint -> {};
        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(2, fixture.saved.size());
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, preparations.get());
        assertEquals(1, setupAttempts.get());
    }
}
