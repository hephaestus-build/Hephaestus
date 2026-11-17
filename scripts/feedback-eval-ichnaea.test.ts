import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { main, testMeasure } from "@hephaestus-build/ichnaea";
import { digest } from "@hephaestus-build/ichnaea/contracts";

import {
	changeDeclaration,
	decideCompositions,
	EXPECTED_CAUSES,
	ichnaeaBuild,
	jobSummary,
	NOISE_PLAN_ICHNAEA,
	SENSITIVITY,
	readVerdict,
	verdictOutput,
} from "./feedback-eval-decision.ts";
import { parseCompositions, type Review, type RunFile } from "./feedback-eval/compositions.ts";
import { expectedRaises, expectedWithholds } from "./feedback-eval/ichnaea-cases.ts";
import {
	casesInReplicate,
	composeEnvironment,
	endpointFailure,
	forget,
} from "./feedback-eval/ichnaea-compose.ts";
import {
	composerConfiguration,
	composerSettings,
	GATE_FILE,
	PROMPT_FILES,
} from "./feedback-eval/ichnaea-configuration.ts";
import { records } from "./feedback-eval/ichnaea-generate.ts";
import { hephaestus } from "./feedback-eval/ichnaea-plugin.ts";
import { isRecord, parseJson } from "./lib/json.ts";

const here = path.join(import.meta.dirname, "feedback-eval", "ichnaea");
const set = parseCompositions(
	parseJson(readFileSync("scripts/feedback-eval/compositions.json", "utf8")),
);

void test("the Ichnaea records are current: run node scripts/feedback-eval/ichnaea-generate.ts", async () => {
	const fresh = await records();
	assert.equal(readFileSync(path.join(here, "cases.jsonl"), "utf8"), fresh.cases);
	assert.equal(readFileSync(path.join(here, "case-set.json"), "utf8"), fresh.caseSet);
	assert.equal(readFileSync(path.join(here, "suite.json"), "utf8"), fresh.suite);
});

void test("every measure passes its known-good and known-bad examples", async () => {
	for (const measure of hephaestus.measures) {
		const result = await testMeasure(measure);
		assert.deepEqual(result.failures, [], measure.card.id);
	}
});

void test("the 29 cases with an expected withholding run 3 trials per arm, every other case 2", () => {
	const withhold = set.cases
		.filter((item) => expectedWithholds(item).length > 0)
		.map((item) => item.id);
	assert.equal(withhold.length, 29);
	assert.equal(casesInReplicate(0).length, 72);
	assert.equal(casesInReplicate(1).length, 72);
	assert.deepEqual(casesInReplicate(2), withhold);
	assert.deepEqual(casesInReplicate(3), []);
	assert.equal(set.cases.filter((item) => expectedRaises(item).length > 0).length, 56);
});

/** One arm's result files: each case's reference composition, changed by `review`, for every replicate it runs in. */
function writeArm(dir: string, arm: string, review: (reference: Review) => Review): void {
	for (const k of [0, 1, 2]) {
		const ids = new Set(casesInReplicate(k));
		const run: RunFile = {
			version: 1,
			commit: (arm === "base" ? "a" : "b").repeat(40),
			model: null,
			runner: null,
			cases: set.cases
				.filter((item) => ids.has(item.id))
				.map((item) => {
					const reference = item.compositions.find((composition) => composition.id === "reference");
					assert.ok(reference, `${item.id} has a reference composition`);
					return {
						id: item.id,
						accepted: true,
						failure: null,
						errors: [],
						review: review(reference.review),
					};
				}),
		};
		writeFileSync(path.join(dir, `${arm}-r${k}.json`), JSON.stringify(run));
	}
}

function configs(
	dir: string,
	candidateModel = "model-a",
	candidateRoot = ".",
): { base: string; candidate: string } {
	const write = (name: string, root: string, model: string, commit: string): string => {
		const file = path.join(dir, `${name}.json`);
		const env = {
			HEPHAESTUS_LIVE_LLM_MODEL: model,
			HEPHAESTUS_LIVE_LLM_BASE_URL: "https://llm.invalid/v1",
		};
		writeFileSync(file, JSON.stringify(composerConfiguration(root, env, commit)));
		return file;
	};
	return {
		base: write("base-config", ".", "model-a", "a".repeat(40)),
		candidate: write("candidate-config", candidateRoot, candidateModel, "b".repeat(40)),
	};
}

/** Both arms' result files and configurations in a new directory. */
function fixture(
	candidate: (reference: Review) => Review,
	candidateModel?: string,
	candidateRoot?: string,
) {
	const dir = mkdtempSync(path.join(tmpdir(), "feedback-eval-decision-"));
	writeArm(dir, "base", (review) => review);
	writeArm(dir, "candidate", candidate);
	return { dir, ...configs(dir, candidateModel, candidateRoot) };
}

type GatePanel = Record<string, unknown>;

/**
 * A checkout with this checkout's prompts and `gate.json`, its panel changed by `panel`. With `gate: false` it has no
 * `gate.json`.
 */
function checkout(
	options: { panel?: (panel: GatePanel) => GatePanel; gate?: boolean } = {},
): string {
	const root = mkdtempSync(path.join(tmpdir(), "feedback-eval-checkout-"));
	for (const file of PROMPT_FILES) {
		mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
		copyFileSync(file, path.join(root, file));
	}
	if (options.gate !== false) {
		const gate = parseJson(readFileSync(GATE_FILE, "utf8"));
		assert.ok(isRecord(gate) && isRecord(gate.panel));
		const panel = options.panel ?? ((unchanged: GatePanel) => unchanged);
		mkdirSync(path.dirname(path.join(root, GATE_FILE)), { recursive: true });
		writeFileSync(
			path.join(root, GATE_FILE),
			JSON.stringify({ ...gate, panel: panel(gate.panel) }),
		);
	}
	return root;
}

/** A pull request's `gate.json`: other composer settings than main's. */
const changedSettings = (panel: GatePanel): GatePanel => ({
	...panel,
	reasoningEffort: "HIGH",
	maxOutputTokens: 4096,
});

interface Replay {
	candidateModel?: string;
	/** The checkout that the candidate's configuration is written from; this checkout when absent. */
	candidateRoot?: string;
	/** The paths that differ between the arms' commits. */
	changed?: readonly string[];
	/** Changes the candidate's configuration after it is written. */
	candidateConfig?: (config: Record<string, unknown>) => Record<string, unknown>;
	/** Changes the suite: a test can turn the shipped suite's registered margins back into placeholders. */
	suite?: (suite: Record<string, unknown>) => Record<string, unknown>;
}

async function replay(candidate: (reference: Review) => Review, options: Replay = {}) {
	const {
		dir,
		base,
		candidate: candidateConfig,
	} = fixture(candidate, options.candidateModel, options.candidateRoot);
	if (options.candidateConfig !== undefined) {
		const config = parseJson(readFileSync(candidateConfig, "utf8"));
		assert.ok(isRecord(config));
		writeFileSync(candidateConfig, JSON.stringify(options.candidateConfig(config)));
	}
	let suite: string | undefined;
	if (options.suite !== undefined) {
		const shipped = parseJson(readFileSync(path.join(here, "suite.json"), "utf8"));
		assert.ok(isRecord(shipped));
		suite = path.join(dir, "suite.json");
		writeFileSync(suite, JSON.stringify(options.suite(shipped)));
	}
	const lines: string[] = [];
	const store = path.join(dir, "store");
	const outcome = await decideCompositions({
		results: dir,
		baseConfig: base,
		candidateConfig,
		changed: options.changed ?? [],
		store,
		run: "replay",
		now: "2026-11-15T12:00:00Z",
		log: (line) => {
			lines.push(line);
		},
		...(suite === undefined ? {} : { suite }),
	});
	return { ...outcome, lines, store };
}

/** The shipped suite with every margin a placeholder, as a suite has it before its owner registers the margins. */
const placeholders = (suite: Record<string, unknown>): Record<string, unknown> => ({
	...suite,
	primary: Array.isArray(suite.primary)
		? suite.primary.map((primary: unknown) => ({
				...(typeof primary === "object" && primary !== null ? primary : {}),
				marginStatus: "placeholder",
			}))
		: suite.primary,
});

const raiseEverything = (review: Review): Review => ({
	...review,
	decisions: review.decisions.map((decision) => ({ ...decision, disposition: "RAISE" as const })),
});

void test("P1 to P4 carry their owner's meaningful-drop caps as registered margins, each with its reason", () => {
	const suite = parseJson(readFileSync(path.join(here, "suite.json"), "utf8"));
	assert.ok(isRecord(suite));
	const { primary } = suite;
	assert.ok(Array.isArray(primary));
	assert.deepEqual(
		primary.map((measure: unknown) => {
			assert.ok(isRecord(measure));
			assert.match(String(measure.marginReason), /\S.*Registered by/u);
			return [measure.measure, measure.margin, measure.marginStatus];
		}),
		[
			["hephaestus/accepted", 0.1, "registered"],
			["hephaestus/mechanically-clean", 0.05, "registered"],
			["hephaestus/raises-expected", 0.1, "registered"],
			["hephaestus/withholds-expected", 0.05, "registered"],
		],
	);
});

void test("no verdict comes from a placeholder margin: an unchanged candidate is estimate-only, as expected", async () => {
	const { codes, summary, lines, verdict } = await replay((review) => review, {
		suite: placeholders,
	});
	assert.deepEqual(codes, [0, 0, 0], lines.join("\n"));
	assert.ok(verdict);
	assert.equal(verdict.verdict, "ESTIMATE-ONLY");
	assert.equal(verdict.reason, EXPECTED_CAUSES.placeholderMargins);
	assert.equal(verdict.expected, true);
	assert.ok(
		(summary ?? "").startsWith(
			`**Expected result.** Estimate-only: ${EXPECTED_CAUSES.placeholderMargins}`,
		),
		summary ?? "",
	);
	assert.doesNotMatch(summary ?? "", /\*\*PASS\*\*/u);
});

void test("no verdict comes from a placeholder margin: a candidate that raises every result is estimate-only too", async () => {
	const { codes, lines, verdict } = await replay(raiseEverything, { suite: placeholders });
	// Estimate-only reports and never blocks, whatever the estimates show.
	assert.deepEqual(codes, [0, 0, 0], lines.join("\n"));
	assert.ok(verdict);
	assert.equal(verdict.verdict, "ESTIMATE-ONLY");
	assert.deepEqual(verdict.regressed, []);
});

void test("with registered margins, a candidate that raises every result is a REGRESSION on withholdings and blocks", async () => {
	const { codes, summary, lines, verdict } = await replay(raiseEverything);
	assert.deepEqual(codes, [0, 1, 0], lines.join("\n"));
	// What the suite can detect stays on every summary, the blocking ones included.
	assert.ok((summary ?? "").startsWith(`${SENSITIVITY}\n\n`), summary ?? "");
	assert.match(summary ?? "", /\*\*REGRESSION\*\* on these 72 cases/u);
	assert.match(summary ?? "", /hephaestus\/withholds-expected/u);
	// The blocking exit status does not hide the verdict: the check run reads the verdict, and names the measures.
	assert.ok(verdict);
	assert.equal(verdict.verdict, "REGRESSION");
	assert.equal(verdict.expected, false);
	assert.ok(verdict.regressed.includes("hephaestus/withholds-expected"), String(verdict.regressed));
	assert.equal(verdict.scope, "on these 72 cases, beyond run-to-run noise");
});

void test("with registered margins, an unchanged candidate is INCONCLUSIVE until the margin floor is set, as expected", async () => {
	const { codes, summary, lines, verdict } = await replay((review) => review);
	// INCONCLUSIVE reports and never blocks.
	assert.deepEqual(codes, [0, 0, 0], lines.join("\n"));
	assert.ok(verdict);
	assert.equal(verdict.verdict, "INCONCLUSIVE");
	assert.equal(verdict.reason, EXPECTED_CAUSES.floorNotSet);
	assert.equal(verdict.expected, true);
	assert.ok(
		(summary ?? "").startsWith(
			`**Expected result.** INCONCLUSIVE: ${EXPECTED_CAUSES.floorNotSet}\n\n${SENSITIVITY}\n\n`,
		),
		summary ?? "",
	);
});

void test("a code change whose arms run different models is INVALID, never decided", async () => {
	const { codes, lines, verdict } = await replay((review) => review, {
		candidateModel: "model-b",
		changed: ["server/application/src/main/resources/agent/review-composer.ts"],
	});
	assert.equal(codes[1], 1, lines.join("\n"));
	assert.match(lines.join("\n"), /^INVALID: .*model\.composer/mu);
	assert.ok(verdict);
	assert.equal(verdict.verdict, "INVALID");
	assert.equal(verdict.expected, false);
});

const COMPOSER = "server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/composer";

void test("each changed path declares what it varies, and every change keeps the code trigger", () => {
	const [prompt, other] = PROMPT_FILES;
	// Prompts: each changed prompt file, by its name in the configuration.
	assert.deepEqual(changeDeclaration([prompt]), { trigger: "code", varies: [`prompt.${prompt}`] });
	assert.deepEqual(changeDeclaration(["docs/contributor/practice-feedback-language.mdx"]), {
		trigger: "code",
		varies: ["prompt.docs/contributor/practice-feedback-language.mdx"],
	});
	// Configuration: gate.json holds the composer settings.
	assert.deepEqual(changeDeclaration([GATE_FILE]), {
		trigger: "code",
		varies: ["parameter.composer.maxOutputTokens", "parameter.composer.reasoningEffort"],
	});
	// The model, the practice criteria and the loop are code: no factor of the configuration comes from them.
	for (const code of [
		"server/application/src/main/resources/agent/review-composer-model.ts",
		`${COMPOSER}/ModelSession.java`,
		`${COMPOSER}/CompositionSessions.java`,
		`${COMPOSER}/ComposerRequests.java`,
		"server/application/src/main/resources/practices/default-catalog.json",
		"server/application/src/main/resources/agent/review-composer.ts",
		"server/application/src/main/resources/agent/review-composer-gateway.ts",
		`${COMPOSER}/ComposerLoop.java`,
		"pnpm-lock.yaml",
	]) {
		assert.deepEqual(changeDeclaration([code]), { trigger: "code", varies: [] }, code);
	}
	// Several kinds of path declare their union.
	assert.deepEqual(changeDeclaration([`${COMPOSER}/ModelSession.java`, GATE_FILE, other, prompt]), {
		trigger: "code",
		varies: [
			"parameter.composer.maxOutputTokens",
			"parameter.composer.reasoningEffort",
			`prompt.${prompt}`,
			`prompt.${other}`,
		].toSorted(),
	});
	// A path that no rule knows declares nothing, so an undeclared difference makes the run INVALID.
	assert.deepEqual(
		changeDeclaration(["server/application/src/main/resources/agent/new-prompt.md"]),
		{
			trigger: "code",
			varies: [],
		},
	);
	assert.deepEqual(changeDeclaration([]), { trigger: "code", varies: [] });
});

/** The candidate's configuration with the first prompt's digest changed, as a pull request that edits it has. */
function changedPrompt(config: Record<string, unknown>): Record<string, unknown> {
	assert.ok(Array.isArray(config.prompts));
	return {
		...config,
		prompts: config.prompts.map((prompt: unknown, index) =>
			index === 0 && typeof prompt === "object" && prompt !== null
				? { ...prompt, digest: `sha256:${"0".repeat(64)}` }
				: prompt,
		),
	};
}

void test("a prompt change that declares its prompt is decided, and one that declares nothing is INVALID", async () => {
	const [prompt] = PROMPT_FILES;
	const declared = await replay((review) => review, {
		changed: [prompt],
		candidateConfig: changedPrompt,
	});
	assert.deepEqual(declared.codes, [0, 0, 0], declared.lines.join("\n"));
	assert.equal(declared.verdict?.verdict, "INCONCLUSIVE");
	// A path that no rule knows is the safe choice: the prompt differs undeclared, so no verdict is given.
	const unknown = await replay((review) => review, {
		changed: ["server/application/src/main/resources/agent/new-prompt.md"],
		candidateConfig: changedPrompt,
	});
	assert.equal(unknown.verdict?.verdict, "INVALID", unknown.lines.join("\n"));
	assert.match(unknown.verdict.reason, /prompt\./u);
});

const parametersOf = (config: Record<string, unknown>): unknown => config.parameters;

/** An arm whose result file lies in its checkout. */
const arm = (name: string, root: string) => ({ name, root, file: path.join(root, `${name}.json`) });

void test("each arm composes and records the composer settings of its own gate.json", () => {
	const base = checkout();
	const candidate = checkout({ panel: changedSettings });
	const env = { HEPHAESTUS_LIVE_LLM_MODEL: "model-a" };
	// A changed gate.json: each arm records and composes with its own values.
	assert.deepEqual(parametersOf(composerConfiguration(candidate, env, "b".repeat(40))), [
		{ name: "composer.reasoningEffort", value: "HIGH", status: "pinned", origin: "explicit" },
		{ name: "composer.maxOutputTokens", value: 4096, status: "pinned", origin: "explicit" },
	]);
	const shipped = composerSettings(".");
	assert.deepEqual(parametersOf(composerConfiguration(base, env, "a".repeat(40))), [
		{
			name: "composer.reasoningEffort",
			value: shipped.reasoningEffort,
			status: "pinned",
			origin: "explicit",
		},
		{
			name: "composer.maxOutputTokens",
			value: shipped.maxOutputTokens,
			status: "pinned",
			origin: "explicit",
		},
	]);
	assert.notEqual(shipped.reasoningEffort, "HIGH");
	assert.notEqual(shipped.maxOutputTokens, 4096);
	const baseEnv = composeEnvironment(arm("base", base), "case-1");
	const candidateEnv = composeEnvironment(arm("candidate", candidate), "case-1");
	assert.equal(baseEnv.COMPOSITION_EVAL_REASONING_EFFORT, shipped.reasoningEffort);
	assert.equal(baseEnv.COMPOSITION_EVAL_MAX_OUTPUT_TOKENS, String(shipped.maxOutputTokens));
	assert.equal(candidateEnv.COMPOSITION_EVAL_REASONING_EFFORT, "HIGH");
	assert.equal(candidateEnv.COMPOSITION_EVAL_MAX_OUTPUT_TOKENS, "4096");
	assert.equal(candidateEnv.COMPOSITION_EVAL_CHECKED, path.join(candidate, "candidate.json"));
	assert.equal(candidateEnv.COMPOSITION_EVAL_CASES, "case-1");
	// An unchanged gate.json: the arms record and compose with equal settings.
	const unchanged = checkout();
	assert.deepEqual(
		parametersOf(composerConfiguration(unchanged, env, "b".repeat(40))),
		parametersOf(composerConfiguration(base, env, "a".repeat(40))),
	);
	const { COMPOSITION_EVAL_CHECKED: _base, ...baseSettings } = baseEnv;
	const { COMPOSITION_EVAL_CHECKED: _unchanged, ...unchangedSettings } = composeEnvironment(
		arm("candidate", unchanged),
		"case-1",
	);
	assert.deepEqual(unchangedSettings, baseSettings);
});

void test("an arm without its own gate.json stops the run, and never takes the other arm's settings", () => {
	const missing = checkout({ gate: false });
	const unknown = /has no scripts\/feedback-eval\/gate\.json/u;
	assert.throws(() => composerSettings(missing), unknown);
	assert.throws(() => composerConfiguration(missing, {}, "a".repeat(40)), unknown);
	assert.throws(() => composeEnvironment(arm("base", missing), "case-1"), unknown);
	// The compose script stops before it starts the first live test.
	const out = mkdtempSync(path.join(tmpdir(), "feedback-eval-compose-"));
	const run = spawnSync(
		process.execPath,
		[
			path.join(import.meta.dirname, "feedback-eval", "ichnaea-compose.ts"),
			"--replicate",
			"0",
			"--base",
			missing,
			"--candidate",
			".",
			"--out",
			out,
		],
		{ encoding: "utf8" },
	);
	assert.equal(run.status, 1, run.stderr);
	assert.match(run.stderr, unknown);
});

void test("a gate.json change that declares its settings is decided, and one that declares nothing is INVALID", async () => {
	const candidateRoot = checkout({ panel: changedSettings });
	const declared = await replay((review) => review, { changed: [GATE_FILE], candidateRoot });
	assert.deepEqual(declared.codes, [0, 0, 0], declared.lines.join("\n"));
	assert.equal(declared.verdict?.verdict, "INCONCLUSIVE");
	const undeclared = await replay((review) => review, {
		changed: ["server/application/src/main/resources/agent/new-prompt.md"],
		candidateRoot,
	});
	assert.equal(undeclared.verdict?.verdict, "INVALID", undeclared.lines.join("\n"));
	assert.match(undeclared.verdict.reason, /parameter\.composer\./u);
});

/** Rewrites the decision record of the replay run. */
function rewriteDecision(store: string, change: (record: Record<string, unknown>) => unknown) {
	const file = path.join(store, "runs", "replay", "decision.json");
	const record = parseJson(readFileSync(file, "utf8"));
	assert.ok(isRecord(record));
	writeFileSync(file, JSON.stringify(change(record)));
}

void test("the verdict follows the record's mode, and a record without a known mode stops the step", async () => {
	const { store } = await replay((review) => review);
	assert.equal(readVerdict(store, "replay")?.verdict, "INCONCLUSIVE");
	// Estimate-only mode: no verdict is ESTIMATE-ONLY, and INVALID stays INVALID.
	rewriteDecision(store, (record) => ({ ...record, mode: "estimate-only", verdict: null }));
	assert.equal(readVerdict(store, "replay")?.verdict, "ESTIMATE-ONLY");
	rewriteDecision(store, (record) => ({ ...record, verdict: "INVALID" }));
	assert.equal(readVerdict(store, "replay")?.verdict, "INVALID");
	// Decision mode always has a verdict.
	rewriteDecision(store, (record) => ({ ...record, mode: "decision", verdict: "PASS" }));
	assert.equal(readVerdict(store, "replay")?.verdict, "PASS");
	rewriteDecision(store, (record) => ({ ...record, verdict: null }));
	assert.throws(() => readVerdict(store, "replay"), /decision mode and has no verdict/u);
	// A missing or unknown mode is never read as one of the two.
	rewriteDecision(store, (record) => ({ ...record, mode: "exploratory", verdict: null }));
	assert.throws(() => readVerdict(store, "replay"), /mode/u);
	rewriteDecision(store, ({ mode: _mode, ...record }) => ({ ...record, verdict: "PASS" }));
	assert.throws(() => readVerdict(store, "replay"), /mode/u);
	assert.equal(readVerdict(store, "never-decided"), null);
	assert.equal(verdictOutput({ codes: [2], summary: null, verdict: null }), "");
});

void test("the scope is the record's, kept on one line: a newline in a value never starts another output", async () => {
	const { store } = await replay((review) => review);
	rewriteDecision(store, (record) => ({
		...record,
		scope: "on these 17 cases,\nbeyond run-to-run noise\r\nverdict=PASS",
	}));
	const scoped = readVerdict(store, "replay");
	assert.ok(scoped);
	assert.equal(scoped.scope, "on these 17 cases, beyond run-to-run noise verdict=PASS");
	const lines = verdictOutput({ codes: [0], summary: null, verdict: scoped }).split("\n");
	assert.equal(lines.length, 5);
	assert.equal(lines[3], "scope=on these 17 cases, beyond run-to-run noise verdict=PASS");
});

void test("the script writes the verdict to the step output, with the exit status of the CI policy beside it", async () => {
	const { dir, base, candidate } = fixture((review) => review, "model-b");
	const output = path.join(dir, "github-output");
	writeFileSync(output, "existing=1\n");
	const changed = path.join(dir, "changed-paths");
	writeFileSync(changed, "server/application/src/main/resources/agent/review-composer.ts\n");
	const run = spawnSync(
		process.execPath,
		[
			path.join(import.meta.dirname, "feedback-eval-decision.ts"),
			"--results",
			dir,
			"--base-config",
			base,
			"--candidate-config",
			candidate,
			"--changed",
			changed,
			"--store",
			path.join(dir, "store"),
			"--run",
			"script",
			"--now",
			"2026-11-15T12:00:00Z",
		],
		{ encoding: "utf8", env: { ...process.env, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: "" } },
	);
	// An INVALID run always blocks, so the step fails; its output still says what was decided.
	assert.equal(run.status, 1, run.stderr);
	const lines = readFileSync(output, "utf8").split("\n");
	assert.equal(lines[0], "existing=1");
	assert.equal(lines[1], "verdict=INVALID");
	assert.equal(lines[2], "regressed=");
	assert.match(lines[3] ?? "", /^reason=\S/u);
	assert.match(lines[4] ?? "", /^scope=\S/u);
	assert.equal(lines.length, 6);
});

void test("doctor finds every measure's tester passing", async () => {
	const lines: string[] = [];
	assert.equal(
		await main(
			["doctor", "--config", path.join(import.meta.dirname, "feedback-eval", "ichnaea.config.ts")],
			(line) => {
				lines.push(line);
			},
		),
		0,
	);
	assert.ok(lines.includes("doctor: clean"), lines.join("\n"));
});

/** One case of a run file: accepted without a failure, or failed with it. */
const entry = (id: string, failure: string | null) => ({
	id,
	accepted: failure === null,
	failure,
	errors: [],
	review: null,
});

void test("an attempt that failed at the endpoint, or left no record, runs again; a composer's own failure never does", () => {
	const dir = mkdtempSync(path.join(tmpdir(), "feedback-eval-attempts-"));
	const file = path.join(dir, "base-r0.json");
	writeFileSync(
		file,
		JSON.stringify({
			version: 1,
			model: null,
			runner: null,
			cases: [
				entry("provider", "PROVIDER"),
				entry("capacity", "CAPACITY"),
				entry("refused", "LOOP"),
				entry("fine", null),
			],
		}),
	);
	assert.equal(endpointFailure(file, "provider"), "PROVIDER");
	assert.equal(endpointFailure(file, "capacity"), "CAPACITY");
	// The composer's own failure is a result about the composer: it is decided, never retried until it passes.
	assert.equal(endpointFailure(file, "refused"), null);
	assert.equal(endpointFailure(file, "fine"), null);
	assert.equal(endpointFailure(file, "absent"), "NOT_RECORDED");
	assert.equal(endpointFailure(path.join(dir, "missing.json"), "fine"), "NOT_RECORDED");
	// A retried case leaves the file, so the live test composes it again; every other case stays.
	forget(file, "provider");
	assert.equal(endpointFailure(file, "provider"), "NOT_RECORDED");
	assert.equal(endpointFailure(file, "capacity"), "CAPACITY");
	assert.equal(endpointFailure(file, "refused"), null);
});

void test("every summary names the pinned Ichnaea build and the version that decided the noise plan", () => {
	const { build, source } = ichnaeaBuild();
	// The build that the root manifest pins, never a branch, and its source commit from the installed build.json.
	assert.match(build, /^[0-9a-f]{40}$/u);
	assert.match(source, /^[0-9a-f]{40}$/u);
	const summary = jobSummary("report", null, { build, source });
	assert.ok(
		summary.startsWith(`${SENSITIVITY}\n\n**Decided with** Ichnaea build \`${build.slice(0, 8)}\``),
		summary,
	);
	assert.match(summary, new RegExp(`source \`${source.slice(0, 8)}\``, "u"));
	assert.match(summary, new RegExp(`decided with Ichnaea \`${NOISE_PLAN_ICHNAEA}\``, "u"));
	assert.ok(summary.endsWith("report"));
});

void test("the readiness record names the digest of every measure that the suite decides, so a changed measure needs new noise evidence", async () => {
	// The noise evidence is keyed by measure digest. Ichnaea digests the card, not the scorer, so a scorer change
	// bumps the card's version, and this record then needs its evidence written down again.
	const readiness = parseJson(readFileSync(path.join(here, "readiness.json"), "utf8"));
	const suite = parseJson(readFileSync(path.join(here, "suite.json"), "utf8"));
	assert.ok(isRecord(readiness) && Array.isArray(readiness.measures));
	assert.ok(isRecord(suite) && Array.isArray(suite.primary) && Array.isArray(suite.secondary));
	const decided = [
		...suite.primary.map((p: unknown) => (isRecord(p) ? String(p.measure) : "")),
		...suite.secondary.map(String),
	];
	const current = await Promise.all(
		hephaestus.measures.map(async (m) => ({
			id: m.card.id,
			version: m.card.version,
			digest: await digest(m.card),
		})),
	);
	assert.deepEqual(
		current.map((m) => m.id.replace(/^hephaestus\//u, "")).toSorted(),
		decided.map((id) => id.replace(/^hephaestus\//u, "")).toSorted(),
	);
	assert.deepEqual(readiness.measures, current);
});
