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
	scoreSafety,
	catalogPractices,
	ceilingReached,
	verdict,
	type Panel,
	type SafetyRow,
} from "./feedback-eval/gate.ts";
import { parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		output: { type: "string" },
		model: { type: "boolean", default: false },
		safety: { type: "boolean", default: false },
	},
});
if (values.model && values.safety) {
	throw new Error(
		"--model composes the whole panel, which holds the safety subset; pass one of them",
	);
}
const output = path.resolve(values.output ?? ".context/feedback-eval-gate");
mkdirSync(output, { recursive: true });
const readJson = (file: string): unknown => parseJson(readFileSync(file, "utf8"));

const gate = parseGateConfig(readJson("scripts/feedback-eval/gate.json"));
const catalog = catalogPractices(
	readJson("server/application/src/main/resources/practices/default-catalog.json"),
);
const compositionsFile = readJson("scripts/feedback-eval/compositions.json");

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
	compositions: compositionsFile,
	claims: readJson("scripts/feedback-eval/cases.json"),
	catalog,
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

/** Composes `caseIds`, one bounded live-test invocation each: the checked run, or why there is none. */
function compose(caseIds: string[]): { run: RunFile; model: Model } | Panel {
	if (missing.length > 0) {
		return {
			status: "skipped",
			reason: `No model provider in this run: ${missing.join(", ")} not set. docs/contributor/ci-cd.mdx § Feedback eval panel provider says how CI sets them.`,
		};
	}
	if (!deterministicPassed) {
		return {
			status: "not-run",
			reason: "A deterministic check failed, so the gate sent no model request.",
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
	for (const caseId of caseIds) {
		const stop = ceilingReached(gate, recordedCalls());
		if (stop !== undefined) {
			return { status: "ceiling-reached", reason: stop };
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
	if (recordedCalls().some((each) => each > gate.panel.callsPerCase)) {
		return { status: "ceiling-reached", reason: ceilingReached(gate, recordedCalls()) ?? "" };
	}
	const run: RunFile = existsSync(checked)
		? parseRun(readJson(checked), checked)
		: { version: 1, model: null, runner: null, cases: [] };
	return { run, model };
}

let panel: Panel = {
	status: "not-run",
	reason: values.safety
		? "This run composed the prompt-safety subset only."
		: "This run asked for the deterministic checks only. CI / Feedback eval panel composes the panel (--model) or the safety subset (--safety).",
};
let safety: SafetyRow[] | Panel | null = null;
let model: Model | null = null;
if (values.model) {
	const composed = compose(gate.panel.cases);
	if ("run" in composed) {
		model = composed.model;
		panel = scorePanel(gate, parseCompositions(compositionsFile), composed.run);
	} else {
		panel = composed;
	}
} else if (values.safety) {
	const composed = compose(gate.safety.cases);
	if ("run" in composed) {
		model = composed.model;
		safety = scoreSafety(gate, parseCompositions(compositionsFile), composed.run);
	} else {
		safety = composed;
	}
}
// The safety subset reports and never fails the run; only the checks and the whole panel can.
const result = verdict(checks, panel);
const report = { version: 1, checks, panel, safety, model, verdict: result };
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

function answer(value: boolean | null): string {
	if (value === null) {
		return "n/a";
	}
	return value ? "yes" : "no";
}
const cell = (text: string) => text.replaceAll("|", String.raw`\|`).replaceAll("\n", " ");

function safetyLines(rows: SafetyRow[] | Panel | null): string[] {
	if (rows === null) {
		return [];
	}
	if (!Array.isArray(rows)) {
		const reason = "reason" in rows ? rows.reason : "";
		return ["", `Prompt-safety subset: **${rows.status}**. ${reason}`];
	}
	return [
		"",
		`Prompt-safety subset (${model?.id ?? "unknown model"}), report only. Read whether each review follows an instruction in the work. The release eval's labels judge it.`,
		"",
		"| Case | Expected decisions | Accepted | No mechanical code | What the review says |",
		"| --- | --- | --- | --- | --- |",
		...rows.map((row) =>
			[
				"",
				row.caseId,
				row.decided ? "yes" : "no",
				row.accepted ? "yes" : `no (${row.failure ?? "refused"})`,
				answer(row.mechanicallyClean),
				row.text.map(cell).join("<br>"),
				"",
			]
				.join(" | ")
				.trim(),
		),
	];
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
	...safetyLines(safety),
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
