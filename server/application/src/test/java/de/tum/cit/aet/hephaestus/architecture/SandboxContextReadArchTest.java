package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.run.ReadsContext;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every route that a sandbox calls ({@code /internal/agent/**} and {@code /internal/llm/**}) either rechecks its run's
 * authority before it reads a source ({@link ReadsContext}, ADR 0134) or is named here with why it reads none. A new
 * route that reads a source without the recheck would let a run go on under access that ended.
 */
class SandboxContextReadArchTest extends HephaestusArchitectureTest {

    /** Routes that read no source of the run, with why. */
    private static final Map<String, String> READS_NO_SOURCE = Map.of(
            "AgentRunController.lease", "renews the lease",
            "AgentRunController.end", "ends the run",
            "AgentNextController.next", "claims a run; the run's first source read rechecks it",
            "ConversationTurnController.partial", "sends the attempt's own partial answer to its readers",
            "ConversationTurnController.end", "ends the attempt with what it wrote",
            "LlmProxyController.proxy", "a model call: the model dispatch boundary, not a source read");

    @Test
    void shouldRecheckAuthorityOnEverySandboxRouteThatReadsASource() {
        var unchecked = new TreeSet<String>();
        var routes = new TreeSet<String>();
        for (JavaClass type : classes) {
            boolean sandboxFacing = sandboxFacing(type);
            for (JavaMethod method : type.getMethods()) {
                if (!method.isMetaAnnotatedWith(RequestMapping.class)) continue;
                boolean takesSandboxRun = method.getRawParameterTypes().stream()
                        .anyMatch(parameter -> parameter.isEquivalentTo(SandboxRun.class));
                if (!sandboxFacing && !takesSandboxRun) continue;
                String name = type.getSimpleName() + "." + method.getName();
                routes.add(name);
                if (!method.isAnnotatedWith(ReadsContext.class) && !READS_NO_SOURCE.containsKey(name))
                    unchecked.add(name);
            }
        }

        assertThat(unchecked)
                .as("sandbox routes that neither carry @ReadsContext nor are named as reading no source")
                .isEmpty();
        assertThat(routes).as("every route named as reading no source exists").containsAll(READS_NO_SOURCE.keySet());
    }

    /** A controller whose routes only a sandbox reaches; a route that takes a {@link SandboxRun} counts wherever it is. */
    private static boolean sandboxFacing(JavaClass type) {
        return type.tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> Stream.concat(Arrays.stream(mapping.value()), Arrays.stream(mapping.path()))
                        .anyMatch(path -> path.startsWith("/internal/agent") || path.startsWith("/internal/llm")))
                .orElse(false);
    }
}
