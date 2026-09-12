package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PiRuntimeFactoryTest extends BaseUnitTest {

    private static final PracticeRunnerProfile PRACTICE = new PracticeRunnerProfile();
    private static final MentorRunnerProfile MENTOR = new MentorRunnerProfile();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PiRuntimeFactory factory;

    @BeforeEach
    void setUp() {
        factory = new PiRuntimeFactory(objectMapper);
    }

    private PiPlanSpec spec(String apiProtocol, String modelId, boolean allowInternet) {
        return new PiPlanSpec(
                apiProtocol, modelId, 200000, 8192, false, "job-token-123", allowInternet, 600, PRACTICE, Map.of(), "");
    }

    private PiPlanSpec spec(PiRunnerProfile profile) {
        return new PiPlanSpec(
                "openai-completions",
                "gpt-5.4-mini",
                null,
                null,
                false,
                "job-token-123",
                false,
                600,
                profile,
                Map.of(),
                "");
    }

    @Nested
    class NetworkPolicyContract {

        @Test
        void alwaysForwardsToken() {
            var policy = factory.build(spec("azure-openai-responses", "gpt-5.4-mini", false))
                    .networkPolicy();
            assertThat(policy.internetAccess()).isFalse();
            assertThat(policy.llmProxyToken()).isEqualTo("job-token-123");
        }

        @Test
        void allowInternetReflectsSpec() {
            var policy =
                    factory.build(spec("openai-completions", "gpt-x", true)).networkPolicy();
            assertThat(policy.internetAccess()).isTrue();
            assertThat(policy.llmProxyToken()).isEqualTo("job-token-123");
        }
    }

    @Nested
    class InputFiles {

        @Test
        void loadsClasspathResources() {
            var inputs = factory.build(spec("azure-openai-responses", "gpt-5.4-mini", false))
                    .inputFiles();
            assertThat(inputs.get(SandboxLayout.ORCHESTRATOR_PATH)).isNotNull();
            assertThat(new String(inputs.get(SandboxLayout.ORCHESTRATOR_PATH), StandardCharsets.UTF_8))
                    .contains("observations");
            assertThat(inputs.get(SandboxLayout.RUNNER_SCRIPT_FILENAME)).isNotEmpty();
            assertThat(new String(inputs.get(SandboxLayout.NODE_PACKAGE_JSON_FILENAME), StandardCharsets.UTF_8))
                    .isEqualTo("{\"type\":\"module\"}\n");
        }

        @Test
        @DisplayName("stages the runner's relative-import sidecars beside the runner")
        void stagesRunnerSidecars() {
            var inputs = factory.build(spec("openai-completions", "m", false)).inputFiles();
            // pi-runner.ts imports each relatively; unstaged, the sandbox exits 1 with ERR_MODULE_NOT_FOUND.
            for (String sidecar : PRACTICE.sidecarScripts()) {
                assertThat(inputs).containsKey(sidecar);
                assertThat(inputs.get(sidecar)).isNotEmpty();
            }
            assertThat(PRACTICE.sidecarScripts())
                    .contains("pi-observation-normalize.ts", "pi-runner-usage.ts", "pi-provider.ts");
        }

        @Test
        void promptDigest_isStableAcrossBuilds_andIndependentOfModel() {
            // An evaluation groups runs by this digest; settings.json and pi-provider.json are excluded from it.
            String first =
                    factory.build(spec("openai-completions", "model-a", false)).promptDigest();
            String second =
                    factory.build(spec("openai-completions", "model-b", false)).promptDigest();

            assertThat(first).matches("[0-9a-f]{64}").isEqualTo(second);
        }

        @Test
        void promptDigest_matchesScaffoldingBytes() {
            // The digest is exactly the root digest over orchestrator + runner + sidecars + prompt
            // resources — recomputable from the plan's own input files, so a replay can verify it.
            // Insertion order mirrors PiRuntimeFactory: sidecars, then prompts.
            var plan = factory.build(spec("openai-completions", "m", false));
            var scaffolding = new java.util.LinkedHashMap<String, byte[]>();
            scaffolding.put(SandboxLayout.ORCHESTRATOR_PATH, plan.inputFiles().get(SandboxLayout.ORCHESTRATOR_PATH));
            scaffolding.put(
                    SandboxLayout.RUNNER_SCRIPT_FILENAME, plan.inputFiles().get(SandboxLayout.RUNNER_SCRIPT_FILENAME));
            for (String sidecar : PRACTICE.sidecarScripts()) {
                scaffolding.put(sidecar, plan.inputFiles().get(sidecar));
            }
            for (String prompt : PRACTICE.promptResources()) {
                scaffolding.put(prompt, plan.inputFiles().get(prompt));
            }

            assertThat(plan.promptDigest()).isEqualTo(ProvenanceDigest.rootDigestHex(scaffolding));
        }

        @Test
        void extraInputsMerge() {
            byte[] payload = "deadbeef".getBytes(StandardCharsets.UTF_8);
            PiPlanSpec spec = new PiPlanSpec(
                    "openai-completions",
                    "gpt-x",
                    null,
                    null,
                    false,
                    "job-token-123",
                    true,
                    600,
                    PRACTICE,
                    Map.of(SandboxLayout.CONTEXT_PREFIX + "metadata.json", payload),
                    "");
            assertThat(factory.build(spec).inputFiles()).containsKey(SandboxLayout.CONTEXT_PREFIX + "metadata.json");
        }
    }

    @Nested
    class SettingsJson {

        @Test
        void alwaysUsesHephaestusProvider() throws Exception {
            byte[] json = factory.buildPiSettingsJson("some-model");
            JsonNode root = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            assertThat(root.path("defaultProvider").asString()).isEqualTo("hephaestus");
            assertThat(root.path("defaultModel").asString()).isEqualTo("some-model");
            assertThat(root.path("transport").asString()).isEqualTo("sse");
        }

        @Test
        void omitsModelAndIncludesCompaction() throws Exception {
            byte[] json = factory.buildPiSettingsJson(null);
            JsonNode root = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            assertThat(root.has("defaultModel")).isFalse();
            assertThat(root.path("compaction").path("enabled").asBoolean()).isTrue();
            assertThat(root.path("compaction").path("reserveTokens").asInt()).isEqualTo(16384);
        }

        @Test
        @DisplayName("a session waits out a provider blip rather than ending on the SDK's 14-second default")
        void ridesOutAProviderBlip() throws Exception {
            byte[] json = factory.buildPiSettingsJson(null);
            JsonNode root = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            assertThat(root.path("retry").path("enabled").asBoolean()).isTrue();
            int attempts = root.path("retry").path("maxRetries").asInt();
            int baseDelayMs = root.path("retry").path("baseDelayMs").asInt();
            // What the settings buy, in seconds of provider trouble: the doubling waits before each
            // repeat. Two minutes is the floor this exists for; the review's own budget is the ceiling.
            long ridesOutMs = 0;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                ridesOutMs += (long) baseDelayMs << (attempt - 1);
            }
            assertThat(ridesOutMs).isBetween(120_000L, 600_000L);
        }
    }

    @Nested
    class ProviderConfigJson {

        @Test
        void capturesResolvedBehaviourButNeverTheCredential() throws Exception {
            PiPlanSpec spec = new PiPlanSpec(
                    "openai-completions",
                    "gpt-oss-120b",
                    131072,
                    4096,
                    true,
                    "job-token-123",
                    false,
                    600,
                    PRACTICE,
                    Map.of(),
                    "");
            byte[] json = factory.buildProviderConfigJson(spec);
            JsonNode root = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));

            assertThat(root.path("apiProtocol").asString()).isEqualTo("openai-completions");
            assertThat(root.path("modelId").asString()).isEqualTo("gpt-oss-120b");
            assertThat(root.path("contextWindow").asInt()).isEqualTo(131072);
            assertThat(root.path("maxOutputTokens").asInt()).isEqualTo(4096);
            assertThat(root.path("supportsReasoning").asBoolean()).isTrue();
            assertThat(root.has("cacheControlFormat")).isFalse();
            assertThat(new String(json, StandardCharsets.UTF_8)).doesNotContain("job-token-123");
        }

        @Test
        void omitsNullCapabilityFields() throws Exception {
            byte[] json = factory.buildProviderConfigJson(spec("anthropic-messages", "claude", false));
            JsonNode root = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
            assertThat(root.has("contextWindow")).isTrue(); // spec() helper sets 200000
            assertThat(root.has("cacheControlFormat")).isFalse();
        }

        @Test
        void writtenAtWorkspaceRootFilename() {
            var inputs = factory.build(spec("openai-completions", "m", false)).inputFiles();
            assertThat(inputs).containsKey(SandboxLayout.PROVIDER_CONFIG_FILENAME);
        }
    }

    @Nested
    class Environment {

        @ParameterizedTest
        @CsvSource({"61, 1000", "90, 30000", "119, 59000", "600, 540000", "2147483647, 2147483587000"})
        void shouldReserveShutdownTimeBeforeTheSandboxDeadline(int timeoutSeconds, long expectedBudgetMs) {
            PiPlanSpec spec = new PiPlanSpec(
                    "openai-completions",
                    "gpt-x",
                    null,
                    null,
                    false,
                    "job-token-123",
                    false,
                    timeoutSeconds,
                    PRACTICE,
                    Map.of(),
                    "");

            long budgetMs = Long.parseLong(factory.build(spec).environment().get("AGENT_BUDGET_MS"));

            assertThat(budgetMs).isEqualTo(expectedBudgetMs).isPositive();
            assertThat(budgetMs + 30_000L)
                    .as("the runner's watchdog includes a 30-second shutdown grace")
                    .isLessThan(timeoutSeconds * 1000L);
        }

        @Test
        @DisplayName("HOME / TMPDIR on tmpfs; PI_CODING_AGENT_DIR points into the workspace")
        void writableMounts() {
            var env = factory.build(spec("azure-openai-responses", "gpt-5.4-mini", false))
                    .environment();
            assertThat(env)
                    .containsEntry("HOME", "/home/agent")
                    .containsEntry("TMPDIR", "/home/agent/.local/tmp")
                    .containsEntry("PI_CODING_AGENT_DIR", SandboxLayout.PI_AGENT_DIR);
        }

        @Test
        void uvThreadpoolNotForced() {
            var env = factory.build(spec("openai-completions", "gpt-x", false)).environment();
            assertThat(env).doesNotContainKey("UV_THREADPOOL_SIZE");
        }

        @Test
        @DisplayName(
                "No shell-side credential export — LLM_PROXY_URL/TOKEN come from NetworkPolicy, not AGENT_BUDGET_MS env")
        void noLegacyProviderEnvVars() {
            var env = factory.build(spec("openai-completions", "gpt-x", false)).environment();
            assertThat(env)
                    .doesNotContainKeys(
                            "PI_HEPHAESTUS_BASE_URL",
                            "PI_HEPHAESTUS_API_KEY",
                            "PI_HEPHAESTUS_MODEL",
                            "AZURE_OPENAI_API_KEY",
                            "AZURE_OPENAI_BASE_URL",
                            "AZURE_OPENAI_DEPLOYMENT_NAME_MAP",
                            "OPENAI_API_KEY",
                            "ANTHROPIC_API_KEY");
        }
    }

    @Nested
    class CommandAssembly {

        @Test
        @DisplayName("Practice profile permits native tools without granting subprocesses to mentor")
        void runtimeFlagsForPractice() {
            String body = factory.build(spec("openai-completions", "gpt-x", false))
                    .command()
                    .get(2);
            int nodeIdx = body.indexOf("node ");
            int scriptIdx = body.indexOf(SandboxLayout.RUNNER_SCRIPT_FILENAME);

            assertThat(body.substring(nodeIdx, scriptIdx))
                    .contains("--max-old-space-size=256")
                    .doesNotContain("--permission", "--allow-child-process", "--allow-fs-");
            assertThat(MENTOR.runtimeFlags()).doesNotContain("--allow-child-process");
            int lastAmp = body.lastIndexOf("&&", nodeIdx);
            int sliceStart = lastAmp >= 0 ? lastAmp + 2 : 0;
            assertThat(body.substring(sliceStart, nodeIdx)).isBlank();
        }

        /**
         * A write grant is resolved when Node starts, so a directory the command grants but does not
         * create first accepts its own {@code mkdir} and denies every write inside it.
         */
        @Test
        void shouldCreateEveryDirectoryTheMentorIsGrantedBeforeNodeStarts() {
            PiRunnerProfile profile = MENTOR;
            String body = factory.build(spec(profile)).command().get(2);
            int runner = body.lastIndexOf("node ");
            int mkdir = body.indexOf("mkdir -p ");
            assertThat(mkdir).isNotNegative();
            assertThat(runner).isGreaterThan(mkdir);

            String staging = body.substring(mkdir, runner);
            List<String> granted = Pattern.compile("--allow-fs-write=(\\S+)")
                    .matcher(body.substring(runner))
                    .results()
                    .map(match -> match.group(1))
                    .toList();
            assertThat(granted).isNotEmpty();
            for (String directory : granted) {
                assertThat(staging)
                        .as("%s creates %s before node starts", profile.runnerScript(), directory)
                        .contains(" " + directory + " ");
            }
        }

        @Test
        void mentorProfileContributesMentorFlags() {
            String body = factory.build(spec(MENTOR)).command().get(2);
            int nodeIdx = body.indexOf("node ");
            int scriptIdx = body.indexOf(SandboxLayout.RUNNER_SCRIPT_FILENAME);

            assertThat(body.substring(nodeIdx, scriptIdx))
                    .contains("--max-old-space-size=256")
                    .contains("--expose-gc")
                    .contains("--permission");
            assertThat(body)
                    .contains("--allow-fs-read=/workspace")
                    .contains("--allow-fs-read=/home/agent")
                    .contains("--allow-fs-write=/workspace/.pi")
                    .contains("--allow-fs-write=/workspace/out");
        }

        @Test
        void profilesResolveToDistinctScripts() {
            PiPlanSpec base = spec("azure-openai-responses", "gpt-5.4-mini", false);
            byte[] practiceBytes = factory.build(base).inputFiles().get(SandboxLayout.RUNNER_SCRIPT_FILENAME);
            byte[] mentorBytes = factory.build(spec(MENTOR)).inputFiles().get(SandboxLayout.RUNNER_SCRIPT_FILENAME);
            assertThat(mentorBytes).isNotEqualTo(practiceBytes);
        }

        @Test
        void shCommandWithPrecomputeOrder() {
            PiPlanSpec spec = new PiPlanSpec(
                    "openai-completions",
                    "gpt-x",
                    null,
                    null,
                    false,
                    "job-token-123",
                    true,
                    600,
                    PRACTICE,
                    Map.of(),
                    "echo precompute && ");
            var plan = factory.build(spec);
            assertThat(plan.command()).hasSize(3);
            assertThat(plan.command().get(0)).isEqualTo("sh");
            String body = plan.command().get(2);
            assertThat(body.indexOf("echo precompute"))
                    .isLessThan(
                            body.indexOf(SandboxLayout.WORKSPACE_ROOT + "/" + SandboxLayout.RUNNER_SCRIPT_FILENAME));
        }

        @Test
        @DisplayName("No shell-side cp/export of Pi config — provider.json + settings.json are the whole contract")
        void noCopyShim() {
            assertThat(factory.build(spec("openai-completions", "gpt-x", false))
                            .command()
                            .get(2))
                    .doesNotContain(" cp ")
                    .doesNotContain("export ");
        }
    }
}
