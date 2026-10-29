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

package bisq.common.processing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Immutable submission admission, initialization bindings, stash types, processor construction and setup for a lane.
 * Initializers match concrete input classes and run only while the lane has no processor.
 * Application initialization commands need not belong to the processor's input family.
 *
 * @param <S> complete processor snapshot
 * @param <I> incoming or raised input accepted by the processor
 * @param <W> one addressed piece of work
 */
public final class ProcessingLaneDefinition<S, I extends Input, W> {
    private final Class<I> processorInputType;
    private final Function<S, Processor<S, I, W>> processorFactory;
    private final Optional<Consumer<? super Input>> submissionCheck;
    private final Map<Class<? extends Input>, Function<Input, Initialization<S, I>>> initializers;
    private final Set<Class<? extends I>> typesToStashUntilInitialized;
    private final Optional<Consumer<? super S>> setup;
    private final Optional<Consumer<? super S>> onInitialized;

    private ProcessingLaneDefinition(Builder<S, I, W> builder) {
        processorInputType = builder.processorInputType;
        processorFactory = builder.processorFactory;
        submissionCheck = builder.submissionCheck;
        initializers = Map.copyOf(builder.initializers);
        typesToStashUntilInitialized = Set.copyOf(builder.typesToStashUntilInitialized);
        setup = builder.setup;
        onInitialized = builder.onInitialized;
    }

    public static <S, I extends Input, W> Builder<S, I, W> builder(
            Class<I> processorInputType,
            Function<S, Processor<S, I, W>> processorFactory) {
        return new Builder<>(processorInputType, processorFactory);
    }

    void checkSubmission(Input input) {
        submissionCheck.ifPresent(check -> check.accept(input));
    }

    /**
     * Classifies an input for an unknown entity ID without preparing or validating the business entity.
     * Registered initializers may create a lane; other inputs must be eligible to wait for initialization.
     */
    public boolean canCreateWaitingLane(Input input) {
        if (initializers.containsKey(input.getClass())) {
            return true;
        }
        return processorInputType.isInstance(input) && canStashUntilInitialized(processorInputType.cast(input));
    }

    boolean canStashUntilInitialized(I input) {
        return !(input instanceof Command) && typesToStashUntilInitialized.stream()
                .anyMatch(type -> type.isInstance(input));
    }

    Optional<Supplier<Initialization<S, I>>> initializerFor(Input input) {
        return Optional.ofNullable(initializers.get(input.getClass()))
                .map(initializer -> () -> initializer.apply(input));
    }

    Processor<S, I, W> createProcessor(S snapshot) {
        return processorFactory.apply(snapshot);
    }

    boolean hasSetup() {
        return setup.isPresent();
    }

    void runSetup(S snapshot) {
        setup.orElseThrow().accept(snapshot);
    }

    void onInitialized(S snapshot) {
        onInitialized.ifPresent(callback -> callback.accept(snapshot));
    }

    I processorInput(Input input) {
        checkArgument(processorInputType.isInstance(input),
                "Input %s is not supported by the processor",
                input.getClass().getSimpleName());
        return processorInputType.cast(input);
    }

    public static final class Builder<S, I extends Input, W> {
        private final Class<I> processorInputType;
        private final Function<S, Processor<S, I, W>> processorFactory;
        private final Map<Class<? extends Input>, Function<Input, Initialization<S, I>>> initializers = new HashMap<>();
        private final Set<Class<? extends I>> typesToStashUntilInitialized = new HashSet<>();
        private Optional<Consumer<? super Input>> submissionCheck = Optional.empty();
        private Optional<Consumer<? super S>> setup = Optional.empty();
        private Optional<Consumer<? super S>> onInitialized = Optional.empty();

        private Builder(Class<I> processorInputType, Function<S, Processor<S, I, W>> processorFactory) {
            this.processorInputType = processorInputType;
            this.processorFactory = processorFactory;
        }

        /**
         * Checks each new router submission before buffering or lane creation. Throwing rejects only this input.
         * Runs on the submitting thread outside the router lock; must be short, thread-safe and read-only.
         * Receives no live snapshot. Startup release, lane replay and recovery do not repeat this check.
         */
        public Builder<S, I, W> checkSubmissionWith(Consumer<? super Input> check) {
            submissionCheck = Optional.of(check);
            return this;
        }

        public <T extends Input> Builder<S, I, W> initializeOn(
                Class<T> inputType,
                Function<? super T, Initialization<S, I>> initializer) {
            checkArgument(!initializers.containsKey(inputType),
                    "An initializer is already registered for %s",
                    inputType.getSimpleName());
            initializers.put(inputType, input -> initializer.apply(inputType.cast(input)));
            return this;
        }

        /**
         * Adds types whose instances may wait for initialization, including subclasses and implementations.
         * Defaults to none. Registered initializers take precedence; commands can never be stashed.
         */
        @SafeVarargs
        public final Builder<S, I, W> stashUntilInitialized(Class<? extends I>... inputTypes) {
            for (Class<? extends I> inputType : inputTypes) {
                typesToStashUntilInitialized.add(inputType);
            }
            return this;
        }

        /**
         * Runs under lane ownership after the initial checkpoint and before any protocol step.
         * Return only when required setup has succeeded and leave the snapshot unchanged.
         * Setup must be idempotent and suitable for every restored lane, including after earlier success.
         * Completion is tracked only for the live lane and is not persisted.
         */
        public Builder<S, I, W> setupWith(Consumer<? super S> setup) {
            this.setup = Optional.of(setup);
            return this;
        }

        /**
         * Best-effort notification after a new entity's checkpoint and setup, before its first input.
         * Not replayed on recovery. Required, repeatable setup belongs in setupWith instead.
         */
        public Builder<S, I, W> onInitialized(Consumer<? super S> callback) {
            onInitialized = Optional.of(callback);
            return this;
        }

        public ProcessingLaneDefinition<S, I, W> build() {
            return new ProcessingLaneDefinition<>(this);
        }
    }
}
