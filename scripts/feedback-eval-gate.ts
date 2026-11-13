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
	verdict,
	type Panel,
} from "./feedback-eval/gate.ts";
import { asStringArray, parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		output: { type: "string" },
		model: { type: "boolean", default: false },
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
const checks = deterministic({
	gate,
	compositions: compositionsFile,
	claims: readJson("scripts/feedback-eval/cases.json"),
	catalog,
	read: readText,
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
				reason: "The model panel runs only on a pull request (--model).",
			},
			model: null,
		};
	}
	if (missing.length > 0) {
		return {
			panel: {
				status: "skipped",
				reason: `No model credential in this run: ${missing.join(", ")} not set. A forked pull request never gets secrets.`,
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
	// One invocation for each case keeps each under the live tier's own time limit. A rerun composes
	// only the cases the file lacks, and a failed case is never sent again.
	for (const caseId of Object.keys(gate.panel.cases)) {
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
	const run: RunFile = existsSync(checked)
		? parseRun(readJson(checked), checked)
		: { version: 1, model: null, runner: null, cases: [] };
	return { panel: scorePanel(gate, parseCompositions(compositionsFile), run), model };
}

const { panel, model } = runPanel();
const prLabels = process.env.PR_LABELS ?? "";
const labels = prLabels === "" ? [] : asStringArray(parseJson(prLabels), "PR_LABELS");
const result = verdict(checks, panel, labels.includes(gate.acceptLabel));
const report = { version: 1, gateVersion: gate.version, checks, panel, model, verdict: result };
writeFileSync(path.join(output, "feedback-eval-gate.json"), `${JSON.stringify(report, null, 2)}\n`);

function panelLines(scored: Panel): string {
	if (scored.status === "not-run" || scored.status === "skipped") {
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
	panel.status === "skipped" && !result.pass
		? `\nTo accept the skip, a maintainer adds the \`${gate.acceptLabel}\` label and re-runs this job.`
		: "",
].join("\n");
process.stdout.write(`${lines}\n`);
const summaryFile = process.env.GITHUB_STEP_SUMMARY ?? "";
if (summaryFile !== "") {
	appendFileSync(summaryFile, `${lines}\n`);
}
process.exitCode = result.pass ? 0 : 1;
