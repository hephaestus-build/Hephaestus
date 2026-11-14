import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import { parseCompositions, parseRun, type RunFile } from "./feedback-eval/compositions.ts";
import {
	deterministic,
	parseGateConfig,
	scorePanel,
	catalogPractices,
	ceilingReached,
	verdict,
	readPrompts,
	type Panel,
} from "./feedback-eval/gate.ts";
import { parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		output: { type: "string" },
		model: { type: "boolean", default: false },
		base: { type: "string" },
	},
});
const output = path.resolve(values.output ?? ".context/feedback-eval-gate");
mkdirSync(output, { recursive: true });
const readJson = (file: string): unknown => parseJson(readFileSync(file, "utf8"));
const readText = (file: string) => (existsSync(file) ? readFileSync(file, "utf8") : undefined);

const gate = parseGateConfig(readJson("scripts/feedback-eval/gate.json"));
const catalog = catalogPractices(
	readJson("server/application/src/main/resources/practices/default-catalog.json"),
);
const compositionsFile = readJson("scripts/feedback-eval/compositions.json");

/** The manifest at the merge base. A base without one is the change that adds the gate. */
function baseManifest(): unknown {
	if (values.base === undefined) {
		return;
	}
	const shown = spawnSync("git", ["show", `${values.base}:scripts/feedback-eval/gate.json`], {
		encoding: "utf8",
	});
	if (shown.status !== 0) {
		const listed = spawnSync("git", ["cat-file", "-e", `${values.base}^{commit}`]);
		if (listed.status !== 0) {
			throw new Error(`--base ${values.base} is not a commit in this checkout`);
		}
		return;
	}
	return parseJson(shown.stdout);
}

/**
 * G6: the bundled practice scripts are deterministic, so each runs over the authored corpus and must
 * give its recorded snapshot. An intended change records new snapshots in the same pull request.
 */
function practiceScripts(): string[] {
	const corpus = "docker/agents/practice-scripts/corpus.test.ts";
	const run = spawnSync("node", ["--test", "--test-reporter=tap", corpus], {
		encoding: "utf8",
		maxBuffer: 64 * 1024 * 1024,
	});
	if (run.status === 0) {
		return [];
	}
	const differing = [...run.stdout.matchAll(/^not ok \d+ - (?<name>.+)$/gmu)].map(
		(match) => match.groups?.name,
	);
	return differing.length === 0
		? [`${corpus} failed with exit status ${String(run.status)}; run it to see why`]
		: differing.map(
				(slug) =>
					`${slug ?? "a program"} gives other leads than its snapshot; if intended, run node --test --test-update-snapshots ${corpus}`,
			);
}

const checks = deterministic({
	gate,
	prompts: readPrompts(),
	base: baseManifest(),
	compositions: compositionsFile,
	claims: readJson("scripts/feedback-eval/cases.json"),
	catalog,
	read: readText,
	scripts: practiceScripts(),
});
const deterministicPassed = checks.every((check) => check.problems.length === 0);

const credentials = [
	"HEPHAESTUS_LIVE_LLM_BASE_URL",
	"HEPHAESTUS_LIVE_LLM_API_KEY",
	"HEPHAESTUS_LIVE_LLM_MODEL",
];
const missing = credentials.filter((name) => (process.env[name] ?? "") === "");
interface Model {
	id: string;
	endpointSha256: string;
}

function runPanel(): { panel: Panel; model: Model | null } {
	if (!values.model) {
		return {
			panel: {
				status: "not-run",
				reason:
					"This run asked for the deterministic checks only. CI / Feedback eval panel runs the model panel (--model).",
			},
			model: null,
		};
	}
	if (missing.length > 0) {
		return {
			panel: {
				status: "skipped",
				reason: `No model provider in this run: ${missing.join(", ")} not set. docs/contributor/ci-cd.mdx § Feedback eval panel provider says how CI sets them.`,
			},
			model: null,
		};
	}
	if (!deterministicPassed) {
		return {
			panel: {
				status: "not-run",
				reason: "A deterministic check failed, so the gate sent no model request.",
			},
			model: null,
		};
	}
	const model: Model = {
		id: process.env.HEPHAESTUS_LIVE_LLM_MODEL ?? "",
		// The endpoint can name a private host, so the result keeps only its digest.
		endpointSha256: createHash("sha256")
			.update((process.env.HEPHAESTUS_LIVE_LLM_BASE_URL ?? "").replace(/\/+$/u, ""))
			.digest("hex"),
	};
	const checked = path.join(output, "panel-compositions.json");
	const recordedCalls = (): number[] => {
		if (!existsSync(checked)) {
			return [];
		}
		const file: unknown = readJson(checked);
		const cases =
			typeof file === "object" && file !== null && "cases" in file && Array.isArray(file.cases)
				? (file.cases as unknown[])
				: [];
		// Every HTTP request counts: `requests` when the case recorded it, else its model calls.
		return cases.map((entry) => {
			if (typeof entry !== "object" || entry === null) {
				return gate.panel.callsPerCase;
			}
			if ("requests" in entry && typeof entry.requests === "number") {
				return entry.requests;
			}
			return "calls" in entry && typeof entry.calls === "number"
				? entry.calls
				: gate.panel.callsPerCase;
		});
	};
	// One invocation for each case keeps each under the live tier's own time limit. The composer sends a
	// failed request once (CompositionEvalCases), and a failed case is never composed again.
	for (const caseId of Object.keys(gate.panel.cases)) {
		const stop = ceilingReached(gate, recordedCalls());
		if (stop !== undefined) {
			return { panel: { status: "ceiling-reached", reason: stop }, model };
		}
		spawnSync(
			"./gradlew",
			[":application:liveTest", "--tests", "CompositionEvalLiveTest", "--console=plain"],
			{
				cwd: "server",
				stdio: "inherit",
				env: {
					...process.env,
					COMPOSITION_EVAL_CHECKED: checked,
					COMPOSITION_EVAL_CASES: caseId,
					COMPOSITION_EVAL_REASONING_EFFORT: gate.panel.reasoningEffort,
					COMPOSITION_EVAL_MAX_OUTPUT_TOKENS: String(gate.panel.maxOutputTokens),
				},
			},
		);
	}
	const over = recordedCalls().find((each) => each > gate.panel.callsPerCase);
	if (over !== undefined) {
		return {
			panel: { status: "ceiling-reached", reason: ceilingReached(gate, recordedCalls()) ?? "" },
			model,
		};
	}
	const run: RunFile = existsSync(checked)
		? parseRun(readJson(checked), checked)
		: { version: 1, model: null, runner: null, cases: [] };
	return { panel: scorePanel(gate, parseCompositions(compositionsFile), run), model };
}

const { panel, model } = runPanel();
const result = verdict(checks, panel);
const report = { version: 1, gateVersion: gate.version, checks, panel, model, verdict: result };
writeFileSync(path.join(output, "feedback-eval-gate.json"), `${JSON.stringify(report, null, 2)}\n`);

function panelLines(scored: Panel): string {
	if (
		scored.status === "not-run" ||
		scored.status === "skipped" ||
		scored.status === "ceiling-reached"
	) {
		return `Model panel: **${scored.status}**. ${scored.reason}`;
	}
	return [
		`Model panel: **${scored.status}** (${model?.id ?? "unknown model"}).`,
		"",
		"| Measure | Count | Floor |",
		"| --- | --- | --- |",
		...scored.measures.map((m) => `| ${m.id} ${m.name} | ${m.count} / ${m.of} | ${m.floor} |`),
	].join("\n");
}

const lines = [
	"## Feedback eval gate",
	"",
	`**${result.pass ? "Passed" : "Failed"}.** ${result.reason}`,
	"",
	"| Check | Result |",
	"| --- | --- |",
	...checks.map(
		(check) =>
			`| ${check.id} ${check.name} | ${check.problems.length === 0 ? "passed" : check.problems.join("<br>")} |`,
	),
	"",
	panelLines(panel),
].join("\n");
process.stdout.write(`${lines}\n`);
const summaryFile = process.env.GITHUB_STEP_SUMMARY ?? "";
if (summaryFile !== "") {
	appendFileSync(summaryFile, `${lines}\n`);
}
// The nightly report reads whether the panel passed, fell below a floor or was skipped.
const outputFile = process.env.GITHUB_OUTPUT ?? "";
if (outputFile !== "") {
	appendFileSync(outputFile, `status=${panel.status}\n`);
}
process.exitCode = result.pass ? 0 : 1;
