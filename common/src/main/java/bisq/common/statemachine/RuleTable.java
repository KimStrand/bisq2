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

package bisq.common.statemachine;

import bisq.common.data.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;

/** Typed source/event/handler/target declarations for one flow, independent of its domain. */
public final class RuleTable<S, D, E, C> {
    private final Map<Pair<Class<?>, Class<?>>, Rule<?, ? extends E, ? extends S, D, C, E>> rules;

    private RuleTable(List<Rule<?, ? extends E, ? extends S, D, C, E>> declared) {
        Map<Pair<Class<?>, Class<?>>, Rule<?, ? extends E, ? extends S, D, C, E>> map = new LinkedHashMap<>();
        for (var rule : declared) {
            Pair<Class<?>, Class<?>> key = new Pair<>(rule.from(), rule.on());
            checkArgument(!map.containsKey(key), "A rule exists already for %s", key);
            map.put(key, rule);
        }
        rules = Map.copyOf(map);
    }

    public static <S, D, E, C> Builder<S, D, E, C> builder() {
        return new Builder<>();
    }

    public boolean isInterestedIn(E event) {
        return rules.values().stream().anyMatch(rule -> rule.on().equals(event.getClass()));
    }

    public boolean canHandle(S state, E event) {
        return rules.containsKey(new Pair<>(state.getClass(), event.getClass()));
    }

    public boolean canHandle(S state, Class<? extends E> eventType) {
        return rules.containsKey(new Pair<>(state.getClass(), eventType));
    }

    /** Whether the declared graph still has a path to a matching rule. */
    public boolean canReach(S state, E event) {
        var visited = new HashSet<Class<?>>();
        var remaining = new ArrayDeque<Class<?>>();
        remaining.add(state.getClass());
        while (!remaining.isEmpty()) {
            Class<?> source = remaining.removeFirst();
            if (!visited.add(source)) {
                continue;
            }
            if (rules.containsKey(new Pair<>(source, event.getClass()))) {
                return true;
            }
            rules.values().stream().filter(rule -> rule.from().equals(source))
                    .map(Rule::to).forEach(remaining::addLast);
        }
        return false;
    }

    public boolean isTerminal(S state) {
        return rules.values().stream().noneMatch(rule -> rule.from().equals(state.getClass()));
    }

    public Optional<Transition<? extends S, D, E>> apply(C context, FlowSnapshot<? extends S, D> flow, E event) {
        var rule = rules.get(new Pair<>(flow.state().getClass(), event.getClass()));
        checkArgument(rule != null,
                "%s is not allowed in %s",
                event.getClass().getSimpleName(),
                flow.state().getClass().getSimpleName());
        return rule.run(context, flow, event).map(transition -> transition);
    }

    public Collection<Rule<?, ? extends E, ? extends S, D, C, E>> getRules() {
        return rules.values();
    }

    private void validateAcyclic() {
        var visited = new HashSet<Class<?>>();
        var visiting = new HashSet<Class<?>>();
        for (var rule : rules.values()) {
            validateAcyclic(rule.from(), visited, visiting);
        }
    }

    private void validateAcyclic(Class<?> state, Set<Class<?>> visited, Set<Class<?>> visiting) {
        if (visited.contains(state)) {
            return;
        }
        checkArgument(visiting.add(state),
                "State-changing transitions contain a cycle at %s",
                state.getSimpleName());
        for (var rule : rules.values()) {
            if (rule.from().equals(state) && !rule.to().equals(state)) {
                validateAcyclic(rule.to(), visited, visiting);
            }
        }
        visiting.remove(state);
        visited.add(state);
    }

    public record Rule<S, I extends E, T, D, C, E>(Class<S> from,
                                                   Class<I> on,
                                                   TransitionHandler.Factory<S, I, T, D, C, E> handler,
                                                   Class<T> to) {
        Optional<Transition<T, D, E>> run(C context, FlowSnapshot<?, D> flow, Object event) {
            from.cast(flow.state());
            // S occurs only in the immutable state field checked above.
            @SuppressWarnings("unchecked")
            FlowSnapshot<S, D> source = (FlowSnapshot<S, D>) flow;
            return handler.create(context, source).handle(on.cast(event));
        }
    }

    public static final class Builder<S, D, E, C> {
        private final List<Rule<?, ? extends E, ? extends S, D, C, E>> declared = new ArrayList<>();
        private final List<Function<Class<?>, Rule<?, ? extends E, ? extends S, D, C, E>>> anyStateRules = new ArrayList<>();

        private Builder() {
        }

        public <S0 extends S, E0 extends E, T extends S> Builder<S, D, E, C> rule(
                Class<S0> from,
                Class<E0> on,
                TransitionHandler.Factory<S0, E0, T, D, C, E> handler,
                Class<T> to) {
            declared.add(new Rule<>(from, on, handler, to));
            return this;
        }

        /** Expands to every explicitly declared source state; terminal states remain terminal. */
        public <E0 extends E, T extends S> Builder<S, D, E, C> fromAny(
                Class<E0> on,
                TransitionHandler.Factory<S, E0, T, D, C, E> handler,
                Class<T> to) {
            anyStateRules.add(source -> {
                // rule() guarantees that every source belongs to S. The handler accepts any S.
                @SuppressWarnings("unchecked")
                Class<S> from = (Class<S>) source;
                return new Rule<>(from, on, handler, to);
            });
            return this;
        }

        /** Rejects state-changing cycles; transitions that keep the same state are allowed. */
        public RuleTable<S, D, E, C> build() {
            var expanded = new ArrayList<>(declared);
            var sources = new LinkedHashSet<Class<?>>();
            declared.forEach(rule -> sources.add(rule.from()));
            anyStateRules.forEach(factory -> sources.forEach(source -> expanded.add(factory.apply(source))));
            var table = new RuleTable<S, D, E, C>(expanded);
            table.validateAcyclic();
            return table;
        }
    }
}
