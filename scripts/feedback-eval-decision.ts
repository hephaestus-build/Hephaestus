/**
 * The feedback eval decision: imports both arms' compositions of the composition set into Ichnaea, decides the
 * candidate against the base, and renders the report. docs/contributor/feedback-quality-evals.mdx § Decision on the
 * composition set owns the rules.
 * Usage: node scripts/feedback-eval-decision.ts --results <dir> --base-config <file> --candidate-config <file>
 *          --changed <file> [--store <dir>] [--run <id>] [--now <ISO time>]
 * `<dir>` holds `base-r<k>.json` and `candidate-r<k>.json` from scripts/feedback-eval/ichnaea-compose.ts. `--changed`
 * lists the paths that differ between the arms' commits, one on each line.
 */
import { appendFileSync, existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import { FLOOR_NOT_SET_REASON, main } from "@hephaestus-build/ichnaea";
import { Decision, Plan } from "@hephaestus-build/ichnaea/contracts";

import { GATE_FILE, GATE_PARAMETERS, PROMPT_FILES } from "./feedback-eval/ichnaea-configuration.ts";
import { isRecord, readJsonFileSync } from "./lib/json.ts";

const here = path.join(import.meta.dirname, "feedback-eval", "ichnaea");

export interface DecisionInput {
	results: string;
	baseConfig: string;
	candidateConfig: string;
	/** The paths that differ between the arms' commits. */
	changed: readonly string[];
	store: string;
	run: string;
	now?: string;
	/** Where Ichnaea's command output goes. */
	log?: (line: string) => void;
	/** The suite to decide with. Only a test names another one. */
	suite?: string;
}

/** What the arms may differ in: the trigger, and the factor keys that the change declares as varied (`--vary`). */
export interface ChangeDeclaration {
	trigger: "code";
	varies: string[];
}

/**
 * What the changed paths declare as varied. The arms always run different commits, so the trigger is always `code`,
 * which lets the code and its lockfile differ. A prompt path declares that prompt, and `gate.json` declares the
 * composer settings that it holds. A model, practice criteria or loop path is code and declares nothing more: no
 * factor of the configuration comes from it. A path that this function does not know also declares nothing, so a
 * difference that no path declares makes the run INVALID and never gives a verdict.
 */
export function changeDeclaration(changed: readonly string[]): ChangeDeclaration {
	const paths = new Set(changed);
	const varies = [
		...PROMPT_FILES.filter((file) => paths.has(file)).map((file) => `prompt.${file}`),
		...(paths.has(GATE_FILE)
			? Object.values(GATE_PARAMETERS).map((name) => `parameter.${name}`)
			: []),
	];
	return { trigger: "code", varies: varies.toSorted() };
}

/** What a decision says about the change, apart from how the suite's CI policy treats it. */
export interface DecisionVerdict {
	/** `ESTIMATE-ONLY` is a decision in estimate-only mode without a verdict, for example with a placeholder margin. */
	verdict: "PASS" | "REGRESSION" | "INCONCLUSIVE" | "INVALID" | "ESTIMATE-ONLY";
	/** The measures that regressed, in the record's order. */
	regressed: string[];
	/** The cause, on one line: the suite's words for a result that it expects today, else the record's reason. */
	reason: string;
	/** True when the suite expects this result today, so that it does not read as a broken change or setup. */
	expected: boolean;
	/** What the verdict is about, on one line: for example "on these 72 cases, beyond run-to-run noise". */
	scope: string;
}

export interface DecisionOutcome {
	/** Exit codes of import, decide and report; decide's code follows the suite's CI policy. */
	codes: number[];
	/** The Markdown summary, when the report was rendered. */
	summary: string | null;
	/** The verdict of the decision record, whatever the exit code; null when no decision was recorded. */
	verdict: DecisionVerdict | null;
}

/** One line of at most `limit` characters, so a value cannot add an output line or a check title of any length. */
function oneLine(text: string, limit: number): string {
	return text
		.replaceAll(/[\p{Cc}\p{Cf}\s]+/gu, " ")
		.trim()
		.slice(0, limit);
}

/** The causes that the suite expects today, in the words of the check title and the job summary. */
export const EXPECTED_CAUSES = {
	placeholderMargins:
		"margins not registered. A placeholder margin gives estimates and no verdict until its owner registers it.",
	floorNotSet:
		"expected for now. No measure regressed, and no change can pass until the validated margin floor is set.",
} as const;

/**
 * Reads the verdict from the decision record that `decide` wrote, or null when it wrote none. The record's mode says
 * whether a missing verdict is estimate-only. A record in decision mode always has a verdict, so a record without
 * one, or with a mode that the contract does not know, stops the step.
 */
export function readVerdict(store: string, run: string): DecisionVerdict | null {
	const dir = path.join(store, "runs", encodeURIComponent(run));
	const file = path.join(dir, "decision.json");
	if (!existsSync(file)) {
		return null;
	}
	const decision = Decision.parse(JSON.parse(readFileSync(file, "utf8")));
	let verdict: DecisionVerdict["verdict"];
	let cause = decision.reason;
	let expected = false;
	if (decision.verdict !== null) {
		({ verdict } = decision);
		if (verdict === "INCONCLUSIVE" && decision.reason === FLOOR_NOT_SET_REASON) {
			cause = EXPECTED_CAUSES.floorNotSet;
			expected = true;
		}
	} else if (decision.mode === "estimate-only") {
		verdict = "ESTIMATE-ONLY";
		const plan = Plan.parse(JSON.parse(readFileSync(path.join(dir, "plan.json"), "utf8")));
		if (plan.primary.some((measure) => measure.marginStatus === "placeholder")) {
			cause = EXPECTED_CAUSES.placeholderMargins;
			expected = true;
		}
	} else {
		throw new Error(`the decision of run '${run}' is in ${decision.mode} mode and has no verdict`);
	}
	return {
		verdict,
		regressed: decision.measures
			.filter((measure) => measure.verdict === "REGRESSION")
			.map((measure) => oneLine(measure.measure, 80)),
		reason: oneLine(cause, 160),
		expected,
		scope: oneLine(decision.scope, 160),
	};
}

/**
 * What a decision on this suite can detect, from the noise study of the first no-change run. It stays on every summary
 * until the suite has more withhold cases: docs/contributor/feedback-quality-evals.mdx § Noise plan.
 */
export const SENSITIVITY =
	"**What this decision can detect.** P4 detects with 90 % power only drops of 20 points or more; π₀ = 36 %.";

/** The Ichnaea version that decided the composer noise plan, whose numbers SENSITIVITY states. */
export const NOISE_PLAN_ICHNAEA = "da4e52e";

/**
 * The installed Ichnaea: the build commit that the root manifest pins, and the source commit that its build.json
 * records. A summary names it, because the noise plan's numbers come from another version.
 */
export function ichnaeaBuild(): { build: string; source: string } {
	const manifest = fileURLToPath(import.meta.resolve("@hephaestus-build/ichnaea/package.json"));
	const info = readJsonFileSync(path.join(path.dirname(manifest), "build.json"));
	const root = readJsonFileSync(path.join(import.meta.dirname, "..", "package.json"));
	const pin =
		isRecord(root) && isRecord(root.devDependencies)
			? root.devDependencies["@hephaestus-build/ichnaea"]
			: undefined;
	const build =
		typeof pin === "string" ? /#(?<commit>[0-9a-f]{40})$/u.exec(pin)?.groups?.commit : undefined;
	const source =
		isRecord(info) && typeof info.sourceCommit === "string" ? info.sourceCommit : undefined;
	if (build === undefined || source === undefined) {
		throw new Error(
			"the root package.json pins no Ichnaea build commit, or its build.json names no source commit",
		);
	}
	return { build, source };
}

/**
 * The job summary: Ichnaea's report, after one line that says so when the suite expects the result today, the line
 * that says what the decision can detect, and the line that names the Ichnaea that decided.
 */
export function jobSummary(
	report: string,
	verdict: DecisionVerdict | null,
	ichnaea: { build: string; source: string } = ichnaeaBuild(),
): string {
	const expected =
		verdict?.expected === true
			? `**Expected result.** ${verdict.verdict === "ESTIMATE-ONLY" ? "Estimate-only" : verdict.verdict}: ${verdict.reason}\n\n`
			: "";
	const decidedWith = `**Decided with** Ichnaea build \`${ichnaea.build.slice(0, 8)}\` (source \`${ichnaea.source.slice(0, 8)}\`). The noise plan behind the line above was decided with Ichnaea \`${NOISE_PLAN_ICHNAEA}\`.`;
	return `${expected}${SENSITIVITY}\n\n${decidedWith}\n\n${report}`;
}

/** The step output that the `report` job maps to the check run: one `name=value` line each. */
export function verdictOutput(outcome: DecisionOutcome): string {
	const { verdict } = outcome;
	return verdict === null
		? ""
		: `verdict=${verdict.verdict}\nregressed=${verdict.regressed.join(", ")}\nreason=${verdict.reason}\nscope=${verdict.scope}\n`;
}

/** Imports, decides and reports one run. Each step runs only if the one before it succeeded. */
export async function decideCompositions(input: DecisionInput): Promise<DecisionOutcome> {
	const log =
		input.log ??
		((line: string) => {
			process.stdout.write(`${line}\n`);
		});
	const config = ["--config", path.join(import.meta.dirname, "feedback-eval", "ichnaea.config.ts")];
	const files = readdirSync(input.results)
		.filter((name) => /^(?:base|candidate)-r\d+\.json$/u.test(name))
		.toSorted()
		.flatMap((name) => ["--file", path.join(input.results, name)]);
	const now = input.now === undefined ? [] : ["--now", input.now];
	const declared = changeDeclaration(input.changed);
	const codes: number[] = [
		await main(
			[
				"import",
				...config,
				"--importer",
				"hephaestus/composition-runs",
				"--suite",
				input.suite ?? path.join(here, "suite.json"),
				"--case-set",
				path.join(here, "case-set.json"),
				"--cases",
				path.join(here, "cases.jsonl"),
				"--arm",
				`base:base:${input.baseConfig}`,
				"--arm",
				`candidate:candidate:${input.candidateConfig}`,
				...files,
				"--run",
				input.run,
				"--store",
				input.store,
				// Ichnaea makes the run INVALID when the arms differ in anything that is not declared here.
				"--trigger",
				declared.trigger,
				...declared.varies.flatMap((key) => ["--vary", key]),
				...now,
			],
			log,
		),
	];
	if (codes[0] !== 0) {
		return { codes, summary: null, verdict: null };
	}
	codes.push(
		await main(["decide", ...config, "--store", input.store, "--run", input.run, ...now], log),
	);
	// A decision that blocks still gets its report: the report shows why.
	if (codes[1] === 2) {
		return { codes, summary: null, verdict: null };
	}
	const verdict = readVerdict(input.store, input.run);
	codes.push(await main(["report", ...config, "--store", input.store, "--run", input.run], log));
	const summary = path.join(
		input.store,
		"runs",
		encodeURIComponent(input.run),
		"report",
		"summary.md",
	);
	return {
		codes,
		summary: codes[2] === 0 ? jobSummary(readFileSync(summary, "utf8"), verdict) : null,
		verdict,
	};
}

if (import.meta.main) {
	const { values } = parseArgs({
		options: {
			results: { type: "string" },
			"base-config": { type: "string" },
			"candidate-config": { type: "string" },
			changed: { type: "string" },
			store: { type: "string" },
			run: { type: "string" },
			now: { type: "string" },
		},
	});
	const { results } = values;
	const baseConfig = values["base-config"];
	const candidateConfig = values["candidate-config"];
	const { changed } = values;
	if (
		results === undefined ||
		baseConfig === undefined ||
		candidateConfig === undefined ||
		changed === undefined
	) {
		throw new Error("--results, --base-config, --candidate-config and --changed are required");
	}
	const outcome = await decideCompositions({
		results,
		baseConfig,
		candidateConfig,
		changed: readFileSync(changed, "utf8")
			.split("\n")
			.filter((line) => line !== ""),
		store: values.store ?? ".context/feedback-eval-decision",
		run: values.run ?? `run-${Date.now()}`,
		...(values.now === undefined ? {} : { now: values.now }),
	});
	const summaryFile = process.env.GITHUB_STEP_SUMMARY ?? "";
	if (summaryFile !== "" && outcome.summary !== null) {
		appendFileSync(summaryFile, outcome.summary);
	}
	// The check run's colour follows this verdict and never the exit status below: the CI policy decides only
	// whether the job fails.
	const outputFile = process.env.GITHUB_OUTPUT ?? "";
	if (outputFile !== "") {
		appendFileSync(outputFile, verdictOutput(outcome));
	}
	// The step fails on an error (2) or a decision the suite's CI policy blocks (1).
	process.exitCode = outcome.codes.find((code) => code !== 0) ?? 0;
}
