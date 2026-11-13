package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LifecycleContractTest extends HephaestusArchitectureTest {
    private static List<Contract<?>> contracts() {
        return List.of(
                new Contract<>(AgentRunState.class, Set.of(AgentRunState.QUEUED), Set.of(AgentRunState.ENDED)),
                new Contract<>(
                        ActionState.class,
                        Set.of(ActionState.PROPOSED),
                        Set.of(ActionState.DONE, ActionState.UNCERTAIN, ActionState.FAILED, ActionState.CANCELLED)),
                new Contract<>(
                        Account.Status.class,
                        Set.of(Account.Status.UNCLAIMED, Account.Status.ACTIVE),
                        Set.of(Account.Status.DELETED)),
                new Contract<>(
                        IntegrationState.class, Set.of(IntegrationState.PENDING), Set.of(IntegrationState.UNINSTALLED)),
                new Contract<>(
                        ReviewStatus.class,
                        Set.of(ReviewStatus.QUEUED),
                        Set.of(
                                ReviewStatus.COMPLETE,
                                ReviewStatus.PARTIAL,
                                ReviewStatus.FAILED,
                                ReviewStatus.CANCELLED)),
                new Contract<>(
                        PersonDataRequest.State.class,
                        Set.of(PersonDataRequest.State.PREVIEW),
                        Set.of(PersonDataRequest.State.COMPLETE, PersonDataRequest.State.EXPIRED)),
                new Contract<>(
                        Workspace.WorkspaceStatus.class,
                        Set.of(Workspace.WorkspaceStatus.ACTIVE),
                        Set.of(Workspace.WorkspaceStatus.PURGED)));
    }

    @Test
    void shouldWalkEveryProductionLifecycleEnum() {
        var discovered = classes.stream()
                .filter(JavaClass::isEnum)
                .filter(type -> type.isAssignableTo(Lifecycle.class))
                .map(JavaClass::getName)
                .toList();
        assertThat(contracts().stream()
                        .map(contract -> contract.type().getName())
                        .toList())
                .as("Every Lifecycle enum must declare its initial and terminal states in this walk")
                .containsExactlyInAnyOrderElementsOf(discovered);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void shouldGiveTerminalStatesNoExit(Contract<?> contract) {
        contract.checkTerminalStates();
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void shouldReachEveryStateFromAnInitialState(Contract<?> contract) {
        contract.checkReachability();
    }

    private record Contract<S extends Enum<S> & Lifecycle<S>>(Class<S> type, Set<S> initial, Set<S> terminal) {
        void checkTerminalStates() {
            var states = EnumSet.allOf(type);
            for (var state : states) {
                boolean hasExit = states.stream().anyMatch(state::canTransitionTo);
                assertThat(hasExit)
                        .as("%s.%s has an exit if and only if it is nonterminal", type.getSimpleName(), state)
                        .isEqualTo(!terminal.contains(state));
            }
        }

        void checkReachability() {
            assertThat(initial).as("%s initial states", type.getSimpleName()).isNotEmpty();
            assertThat(terminal).as("%s terminal states", type.getSimpleName()).isNotEmpty();
            var states = EnumSet.allOf(type);
            var reached = EnumSet.noneOf(type);
            reached.addAll(initial);
            var pending = new ArrayDeque<>(initial);
            while (!pending.isEmpty()) {
                var current = pending.removeFirst();
                for (var next : states) {
                    if (current.canTransitionTo(next) && reached.add(next)) pending.add(next);
                }
            }
            assertThat(reached)
                    .as("%s states reachable from %s", type.getSimpleName(), initial)
                    .containsExactlyInAnyOrderElementsOf(states);
        }

        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }
}
