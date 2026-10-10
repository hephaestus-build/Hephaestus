package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Cross-language sync test, beside {@code PrecomputeContractSyncTest}. The precompute runner refuses a call with
 * more items than {@code MODEL_SLOT_CAPS} allows as "too-large" before it reaches the proxy. A runner cap above the
 * proxy's turns that reason into a proxy refusal. A runner cap below it refuses calls that the proxy accepts.
 */
class PrecomputeCapsSyncTest extends BaseUnitTest {

    private static final Pattern CAPS = Pattern.compile("MODEL_SLOT_CAPS = \\{([^}]*)}");
    private static final Pattern CAP = Pattern.compile("(\\w+):\\s*([\\d_]+)");

    @Test
    void shouldCapEachPrecomputeCallInTheRunnerAsTheProxyDoes() throws IOException {
        Matcher caps = CAPS.matcher(Files.readString(resolveRepoFile("docker/agents/precompute/lib/contract.ts")));
        assertThat(caps.find()).as("contract.ts declares MODEL_SLOT_CAPS").isTrue();
        Map<String, Integer> runner = CAP.matcher(caps.group(1))
                .results()
                .collect(Collectors.toMap(
                        cap -> cap.group(1),
                        cap -> Integer.parseInt(cap.group(2).replace("_", ""))));

        assertThat(runner)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        ModelKind.DECISION.slot(), LlmProxyService.MAX_DECISION_QUESTIONS,
                        ModelKind.EMBEDDING.slot(), LlmProxyService.MAX_EMBEDDING_INPUTS,
                        ModelKind.RERANKING.slot(), LlmProxyService.MAX_RERANK_DOCUMENTS));
    }

    private static Path resolveRepoFile(String relativePath) {
        Path candidate = Path.of("..", "..").resolve(relativePath);
        return Files.exists(candidate) ? candidate : Path.of(relativePath);
    }
}
