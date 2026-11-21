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

import bisq.common.processing.Command;
import bisq.common.processing.Input;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an entity is initialized: which inputs can start it, and what a rejected or failed initialization does.
 */
class ProcessingLaneInitializationTest extends ProcessingLaneCounterFixture {
    @Test
    void anInitializationMessageCreatesOnceAndLaterCopiesReachTheProcessor() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        var message = new InitializationMessage();
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(message);
        var waiting = fixture.lane.submit(new Message("waiting"));
        var repeated = fixture.lane.submit(message);
        fixture.executor.runAll();

        assertEquals(1, preparations.get());
        assertEquals(List.of(message, new Message("waiting"), message), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(new Counter(3, false), awaitSnapshot(repeated));
    }

    @Test
    void anInitializationOnlyCommandCannotPrepareAnExistingEntityAgain() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.initialize();

        var repeated = fixture.lane.submit(new Initialize(new Message("first")));
        var incremented = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        assertInstanceOf(IllegalArgumentException.class, failure(repeated));
        assertEquals(1, preparations.get());
        assertEquals(List.of(new Action("increment")), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), awaitSnapshot(incremented));
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void beforeInitializationALaneAcceptsOnlyAnInitializer() {
        var fixture = new Fixture();
        // The router keeps inputs which may wait for initialization; a lane never receives them first.
        var early = fixture.lane.submit(new Message("early"));
        var command = fixture.lane.submit(new Action("increment"));
        var notification = fixture.lane.submit(new Notification());
        var unregistered = fixture.lane.submit(new UnregisteredCommand());
        fixture.executor.runAll();

        for (var rejected : List.of(early, command, notification, unregistered)) {
            assertInstanceOf(IllegalStateException.class, failure(rejected));
        }
        assertFalse(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
    }

    @Test
    void initializerAndWaitingEligibilityUseTheDefinitionsRulesWithoutPreparingTheEntity() {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        var definition = fixture.createDefinition(false);

        assertTrue(definition.hasInitializer(new Initialize(new Message("first"))));
        assertTrue(definition.hasInitializer(new InitializationMessage()));
        assertFalse(definition.hasInitializer(new Message("early")));
        assertTrue(definition.canWaitForInitialization(new Message("early")));
        for (var input : List.<Input>of(new Notification(),
                new Action("increment"),
                new UnregisteredCommand(),
                new Input() {})) {
            assertFalse(definition.hasInitializer(input));
            assertFalse(definition.canWaitForInitialization(input));
        }
        assertEquals(0, preparations.get());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void withoutStashDeclarationsOnlyRegisteredInitializersCanStartAnEntity() {
        var fixture = new Fixture();
        var definition = ProcessingLaneDefinition.<Counter, CounterInput>builder(
                        CounterInput.class,
                        fixture::createProcessor,
                        (ignoredSnapshot, ignoredInput) -> {})
                .initializeOn(Initialize.class, input -> fixture.prepare(input, input.firstInput()))
                .build();

        assertTrue(definition.hasInitializer(new Initialize(new Message("first"))));
        assertFalse(definition.canWaitForInitialization(new Message("early")));
        assertFalse(definition.hasInitializer(new InitializationMessage()));
        assertFalse(definition.canWaitForInitialization(new InitializationMessage()));
    }

    @Test
    void aBroadStashTypeCannotMakeCommandsWaitForInitialization() {
        var fixture = new Fixture();
        var definition = ProcessingLaneDefinition.<Counter, CounterInput>builder(
                        CounterInput.class,
                        fixture::createProcessor,
                        (ignoredSnapshot, ignoredInput) -> {})
                .stashUntilInitialized(CounterInput.class)
                .build();
        var lane = new ProcessingLane<>("counter",
                Optional.empty(),
                fixture.executor,
                fixture.timers,
                fixture.clock,
                definition,
                input -> {},
                fixture::saveCheckpoint,
                fixture::executeEffect,
                fixture::publishSnapshot);
        var action = new Action("increment");

        assertTrue(definition.canWaitForInitialization(new Message("early")));
        assertFalse(definition.canWaitForInitialization(action));
        var rejected = lane.submit(action);
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(rejected));
        assertFalse(lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void stashDeclarationsDoNotRestrictInputsAfterInitialization() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();

        var notification = new Notification();
        var result = fixture.lane.submit(notification);
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(result));
        assertEquals(List.of(notification), fixture.attemptedInputs());
    }

    @Test
    void rejectedInitializationClosesTheLaneAndFailsQueuedAndReentrantSubmissions() throws Exception {
        var fixture = new Fixture();
        var rejected = new IllegalArgumentException("Invalid initializer");
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> {
            preparations.incrementAndGet();
            throw rejected;
        };
        var callback = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var waiting = fixture.lane.submit(new Message("waiting"));
        waiting.whenComplete((snapshot, failure) -> callback.set(fixture.lane.submit(new Message("callback"))));
        var queued = fixture.lane.submit(new Message("queued"));
        var anotherInitialization = fixture.lane.submit(new Initialize(new Message("first")));
        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Rejected.class, awaitProcessingSummary(initialized));
        assertSame(rejected, failure(initialized));
        for (var future : List.of(waiting, queued, anotherInitialization)) {
            assertTrue(future.isCompletedExceptionally());
            assertSame(rejected, failure(future));
        }
        assertInstanceOf(RejectedExecutionException.class, failure(callback.get()));
        assertEquals(1, preparations.get());
        assertTrue(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());
    }

    @Test
    void initializationFailureNotificationKeepsTheOriginalRejectionAndStillResolvesAllCallersIfItThrows() throws Exception {
        var fixture = new Fixture();
        var cause = new IllegalArgumentException("Initialization rejected");
        var rejection = new InitializationRejectedException(cause);
        var notificationFailure = new IllegalStateException("Cannot notify router");
        var reported = new ArrayList<Throwable>();
        fixture.onPrepare = input -> { throw rejection; };
        fixture.lane.setOnInitializationFailed(failure -> {
            reported.add(failure);
            throw notificationFailure;
        });
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var waiting = fixture.lane.submit(new Message("waiting"));
        var queued = fixture.lane.submit(new Message("queued"));

        fixture.executor.runAll();

        assertEquals(List.of(rejection), reported);
        assertInstanceOf(ProcessingSummary.Rejected.class, awaitProcessingSummary(initialized));
        assertSame(cause, failure(initialized));
        for (var submission : List.of(waiting, queued)) {
            assertTrue(submission.isCompletedExceptionally());
            assertSame(cause, failure(submission));
        }
        assertEquals(List.of(notificationFailure), List.of(cause.getSuppressed()));
        assertTrue(fixture.lane.isClosed());
        await(fixture.lane.shutdown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aNullInitializationOrProcessorIsAFailedInitialization(boolean nullInitialization) throws Exception {
        var fixture = new Fixture();
        fixture.initializerReturnsNull = nullInitialization;
        fixture.processorFactoryReturnsNull = !nullInitialization;
        var reported = new ArrayList<Throwable>();
        fixture.lane.setOnInitializationFailed(reported::add);
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var queued = fixture.lane.submit(new Message("queued"));

        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Rejected.class, awaitProcessingSummary(initialized));
        var cause = assertInstanceOf(NullPointerException.class, failure(initialized));
        assertEquals(List.of(cause), reported);
        assertTrue(queued.isCompletedExceptionally());
        assertSame(cause, failure(queued));
        assertTrue(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());
    }

    @Test
    void anErrorDuringInitializationIsReportedToTheRouterAndFailsAllCallers() throws Exception {
        var fixture = new Fixture();
        var error = new AssertionError("Initializer failed");
        fixture.onPrepare = input -> { throw error; };
        var reported = new ArrayList<Throwable>();
        fixture.lane.setOnInitializationFailed(reported::add);
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var queued = fixture.lane.submit(new Message("queued"));

        fixture.executor.runAll();

        assertEquals(List.of(error), reported);
        for (var submission : List.of(initialized, queued)) {
            assertTrue(submission.isCompletedExceptionally());
            assertSame(error, failure(submission));
        }
        assertTrue(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());
    }

    @Test
    void aFailedInitialSaveRetainsPreparationAndQueuedMessagesUntilALaterInputRetriesIt() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var saves = new IllegalStateException("Disk unavailable");
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> setupAttempts.incrementAndGet();
        fixture.onSave = checkpoint -> { throw saves; };
        // The router submits pending messages right behind the initializer.
        var initialized = lane.submit(new Initialize(new Message("first")));
        var waiting = lane.submit(new Message("waiting"));
        fixture.executor.runAll();

        assertSame(saves, failure(initialized));
        // The queued message is retained in memory; its own save attempt failed as well.
        var waitingPending = assertInstanceOf(SubmissionResult.Pending.class, await(waiting));
        assertEquals(CoordinationPhase.PERSISTENCE, waitingPending.phase());
        assertInstanceOf(ProcessingSummary.Deferred.class, waitingPending.processingSummary());
        assertFalse(lane.isClosed());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(0, setupAttempts.get());

        fixture.onSave = checkpoint -> {};
        var later = lane.submit(new Action("later"));
        fixture.executor.runAll();

        assertEquals(1, preparations.get());
        assertEquals(1, setupAttempts.get());
        assertEquals(List.of(new Message("first"), new Message("waiting"), new Action("later")), fixture.attemptedInputs());
        assertEquals(new Counter(3, false), awaitSnapshot(later));
    }

    private record UnregisteredCommand() implements Command {}
}
