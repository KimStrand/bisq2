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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTableTest {
    @Test
    void anEmptyDefinitionIsAcyclic() {
        assertDoesNotThrow(() -> newRules().build());
    }

    @Test
    void branchingPathsCanJoinAgain() {
        var builder = newRules()
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Init.class, Alternative.class, unusedHandler(), Waiting.class)
                .rule(Ready.class, Advance.class, unusedHandler(), Finished.class)
                .rule(Waiting.class, Advance.class, unusedHandler(), Finished.class);

        assertDoesNotThrow(builder::build);
    }

    @Test
    void transitionsCanKeepTheSameState() {
        var builder = newRules()
                .rule(Init.class, Stay.class, unusedHandler(), Init.class)
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Ready.class, Stay.class, unusedHandler(), Ready.class);

        assertDoesNotThrow(builder::build);
    }

    @Test
    void aTwoStateCycleIsRejectedBeforeAnyHandlerIsCreated() {
        var builder = newRules()
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Ready.class, Advance.class, unusedHandler(), Init.class);

        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertTrue(exception.getMessage().contains("cycle"));
    }

    @Test
    void aLongerCycleIsRejectedEvenWhenThereAreSameStateTransitions() {
        var builder = newRules()
                .rule(Init.class, Stay.class, unusedHandler(), Init.class)
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Ready.class, Advance.class, unusedHandler(), Waiting.class)
                .rule(Waiting.class, Advance.class, unusedHandler(), Init.class);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void cyclesInDisconnectedPartsOfTheDefinitionAreRejected() {
        var builder = newRules()
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Waiting.class, Advance.class, unusedHandler(), Finished.class)
                .rule(Finished.class, Advance.class, unusedHandler(), Waiting.class);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void fromAnyAppliesToEachSourceButCannotReopenATerminalState() {
        var builder = newRules()
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Ready.class, Advance.class, unusedHandler(), Waiting.class)
                .fromAny(Alternative.class, (context, flow) ->
                        new TransitionHandler<Stage, Alternative, Finished, Integer, Object, Object>(context, flow) {
                            @Override
                            protected Transition<Finished, Integer, Object> process(Alternative event) {
                                return new Transition<>(flow.to(new Finished(), data + 1), List.of());
                            }
                        }, Finished.class);
        var table = builder.build();
        for (Stage state : new Stage[]{new Init(), new Ready()}) {
            var transition = table.apply(new Object(), FlowSnapshot.of(state, 7), new Alternative()).orElseThrow();
            assertEquals(new Finished(), transition.next().state());
            assertEquals(8, transition.next().data());
        }
        assertFalse(table.canHandle(new Waiting(), new Alternative()));
        assertTrue(table.isTerminal(new Waiting()));
        assertTrue(table.isTerminal(new Finished()));
        assertEquals(table.getRules().size(), builder.build().getRules().size());
    }

    @Test
    void fromAnyCannotIntroduceAnAmbiguousRule() {
        var builder = newRules().rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .fromAny(Advance.class, unusedHandler(), Finished.class);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void fromAnyParticipatesInCycleValidation() {
        var builder = newRules()
                .rule(Init.class, Advance.class, unusedHandler(), Ready.class)
                .rule(Ready.class, Advance.class, unusedHandler(), Finished.class)
                .fromAny(Alternative.class, unusedHandler(), Init.class);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    private static RuleTable.Builder<Stage, Integer, Object, Object> newRules() {
        return RuleTable.builder();
    }

    private static <S, E, T> TransitionHandler.Factory<S, E, T, Integer, Object, Object> unusedHandler() {
        return (context, flow) -> {
            throw new AssertionError("Building a definition must not create handlers");
        };
    }

    private interface Stage {
    }

    private record Init() implements Stage {
    }

    private record Ready() implements Stage {
    }

    private record Waiting() implements Stage {
    }

    private record Finished() implements Stage {
    }

    private record Advance() {
    }

    private record Alternative() {
    }

    private record Stay() {
    }
}
