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
import bisq.common.processing.Processor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Immutable initialization bindings, stash types, processor construction and setup for a lane.
 * Initializers match concrete input classes and run only while the lane has no processor.
 * Application initialization commands need not belong to the processor's input family.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
public final class ProcessingLaneDefinition<S, I extends Input> {
    private final Class<I> processorInputType;
    private final Function<S, Processor<S, I>> processorFactory;
    private final Map<Class<? extends Input>, Function<Input, Initialization<S, I>>> initializers;
    private final Set<Class<? extends I>> typesToStashUntilInitialized;
    private final Consumer<? super S> setup;
    private final InputValidator<? super S, ? super I> inputValidator;

    private ProcessingLaneDefinition(Builder<S, I> builder) {
        processorInputType = builder.processorInputType;
        processorFactory = builder.processorFactory;
        initializers = Map.copyOf(builder.initializers);
        typesToStashUntilInitialized = Set.copyOf(builder.typesToStashUntilInitialized);
        setup = builder.setup;
        inputValidator = builder.inputValidator;
    }

    /**
     * Starts a definition. The {@link InputValidator} is required, so a lane cannot forget it. A lane whose inputs
     * need no such check passes an empty validator explicitly.
     */
    public static <S, I extends Input> Builder<S, I> builder(
            Class<I> processorInputType,
            Function<S, Processor<S, I>> processorFactory,
            InputValidator<? super S, ? super I> inputValidator) {
        return new Builder<>(processorInputType, processorFactory, inputValidator);
    }

    /** True if a registered initializer handles this input, which then creates the entity's lane. */
    boolean hasInitializer(Input input) {
        return initializers.containsKey(input.getClass());
    }

    /**
     * True if this input may wait in the router until its entity's initializer arrives. Classifies the input
     * without preparing or validating the business entity.
     */
    boolean canWaitForInitialization(Input input) {
        return processorInputType.isInstance(input) && canStashUntilInitialized(processorInputType.cast(input));
    }

    private boolean canStashUntilInitialized(I input) {
        return !(input instanceof Command) && typesToStashUntilInitialized.stream()
                .anyMatch(type -> type.isInstance(input));
    }

    Optional<Supplier<Initialization<S, I>>> initializerFor(Input input) {
        return Optional.ofNullable(initializers.get(input.getClass()))
                .map(initializer -> () -> initializer.apply(input));
    }

    Processor<S, I> createProcessor(S snapshot) {
        return requireNonNull(processorFactory.apply(snapshot), "The processor factory returned null");
    }

    void runSetup(S snapshot) {
        setup.accept(snapshot);
    }

    I processorInput(Input input) {
        checkArgument(processorInputType.isInstance(input),
                "Input %s is not supported by the processor",
                input.getClass().getSimpleName());
        return processorInputType.cast(input);
    }

    /** Throws if the input may not enter the entity's lane; see {@link InputValidator#validate}. */
    public void validateInput(S snapshot, I input) {
        inputValidator.validate(snapshot, input);
    }

    public static final class Builder<S, I extends Input> {
        private final Class<I> processorInputType;
        private final Function<S, Processor<S, I>> processorFactory;
        private final Map<Class<? extends Input>, Function<Input, Initialization<S, I>>> initializers = new HashMap<>();
        private final Set<Class<? extends I>> typesToStashUntilInitialized = new HashSet<>();
        private Consumer<? super S> setup = snapshot -> {
        };
        private final InputValidator<? super S, ? super I> inputValidator;

        private Builder(Class<I> processorInputType,
                        Function<S, Processor<S, I>> processorFactory,
                        InputValidator<? super S, ? super I> inputValidator) {
            this.processorInputType = processorInputType;
            this.processorFactory = processorFactory;
            this.inputValidator = requireNonNull(inputValidator, "inputValidator");
        }

        public <T extends Input> Builder<S, I> initializeOn(
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
         * The router keeps waiting inputs in a bounded list until the entity's initializer arrives.
         */
        @SafeVarargs
        public final Builder<S, I> stashUntilInitialized(Class<? extends I>... inputTypes) {
            for (Class<? extends I> inputType : inputTypes) {
                typesToStashUntilInitialized.add(inputType);
            }
            return this;
        }

        /**
         * Runs under lane ownership after the initial checkpoint and before the processor handles any input.
         * Return only when required setup has succeeded and leave the snapshot unchanged.
         * Setup must be idempotent and suitable for every restored lane, including after earlier success.
         * Completion is tracked only for the live lane and is not persisted. Without this call, setup does nothing.
         */
        public Builder<S, I> setupWith(Consumer<? super S> setup) {
            this.setup = requireNonNull(setup, "setup");
            return this;
        }

        public ProcessingLaneDefinition<S, I> build() {
            return new ProcessingLaneDefinition<>(this);
        }
    }
}
