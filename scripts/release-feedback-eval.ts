import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import Ajv2020 from "ajv/dist/2020.js";

import {
	compositionReport,
	composedDecisions,
	parseCompositions,
	parseRun,
	parseRunLabels,
	type RunFile,
	mechanicalCodes,
	mechanicalTextCodes,
} from "./feedback-eval/compositions.ts";
import { wilson95 } from "./feedback-eval/statistics.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const { values } = parseArgs({
	options: {
		output: { type: "string" },
		labels: { type: "string" },
		"score-only": { type: "boolean", default: false },
	},
});
if (values.output === undefined || values.output.length === 0) {
	throw new Error("--output names a new directory for this commit's measurement");
}
const output = path.resolve(values.output);
mkdirSync(output, { recursive: true });
const checked = path.resolve(output, "compositions.json");
const read = (file: string): unknown => parseJson(readFileSync(file, "utf8"));
const digest = (file: string) => createHash("sha256").update(readFileSync(file)).digest("hex");
const commit = execFileSync("git", ["rev-parse", "HEAD"], {
	encoding: "utf8",
	env: environmentWithoutGitRepository(),
	maxBuffer: CAPTURE_LIMIT_BYTES,
}).trim();
if (
	execFileSync("git", ["status", "--porcelain", "--untracked-files=all"], {
		encoding: "utf8",
		env: environmentWithoutGitRepository(),
		maxBuffer: CAPTURE_LIMIT_BYTES,
	}).trim()
) {
	throw new Error("Commit the source before measuring a release");
}
for (const name of [
	"COMPOSITION_EVAL_CASES",
	"COMPOSITION_EVAL_REASONING_EFFORT",
	"COMPOSITION_EVAL_MAX_OUTPUT_TOKENS",
]) {
	if (process.env[name] !== undefined) {
		throw new Error(`A release eval does not accept ${name}`);
	}
}
const set = parseCompositions(read("scripts/feedback-eval/compositions.json"));
const publicIds = new Set(set.cases.map((item) => item.id));
const panelSchema = {
	type: "object",
	additionalProperties: false,
	required: ["version", "sourceCases", "destinations", "supportCounts", "expectedActions"],
	properties: {
		version: { const: 1 },
		sourceCases: { type: "array", minItems: 1, uniqueItems: true, items: { type: "string" } },
		destinations: {
			type: "array",
			minItems: 1,
			uniqueItems: true,
			items: { enum: ["PRACTICE_PAGE", "CONVERSATION"] },
		},
		expectedActions: { type: "object", additionalProperties: { enum: ["NEW", "WITHHOLD"] } },
		supportCounts: {
			type: "array",
			minItems: 1,
			uniqueItems: true,
			items: { type: "integer", minimum: 1 },
		},
	},
};
const validatePanel = new Ajv2020({ strict: true }).compile<{
	version: 1;
	sourceCases: string[];
	destinations: string[];
	supportCounts: number[];
	expectedActions: Record<string, "NEW" | "WITHHOLD">;
}>(panelSchema);
const panel: unknown = read("scripts/feedback-eval/private-cases.json");
if (!validatePanel(panel)) {
	throw new Error("Invalid private composition panel");
}
if (panel.sourceCases.some((id) => !publicIds.has(id))) {
	throw new Error("Private panel names an unknown source case");
}
const privateExpected = panel.sourceCases.flatMap((id) =>
	panel.destinations.flatMap((destination) =>
		panel.supportCounts.map((count) => `${id}/${destination}/${count}`),
	),
);
if (
	Object.keys(panel.expectedActions).length !== privateExpected.length ||
	privateExpected.some((id) => panel.expectedActions[id] === undefined)
) {
	throw new Error("Private actions must label every panel case");
}
const occasionFile = path.resolve(output, "occasions.json");
const validateOccasionPanel = new Ajv2020().compile<{ version: 1; cases: string[] }>({
	type: "object",
	required: ["version", "cases"],
	properties: {
		version: { const: 1 },
		cases: { type: "array", minItems: 1, uniqueItems: true, items: { type: "string" } },
	},
});
const occasionPanel: unknown = read("scripts/feedback-eval/occasion-cases.json");
if (!validateOccasionPanel(occasionPanel)) {
	throw new Error("Invalid occasion panel");
}
const executionFile = path.resolve(output, "execution.json");
const validateExecution = new Ajv2020({ strict: true }).compile<{
	version: 1;
	commit: string;
	model: { id: string; reasoningEffort: string; maxOutputTokens: number; endpointSha256: string };
	compositions: { id: string; status: number | null }[];
	occasions: { id: string; status: number | null }[];
}>({
	type: "object",
	required: ["version", "commit", "model", "compositions", "occasions"],
	properties: {
		version: { const: 1 },
		commit: { type: "string" },
		model: {
			type: "object",
			required: ["id", "reasoningEffort", "maxOutputTokens", "endpointSha256"],
			properties: {
				id: { type: "string", minLength: 1 },
				reasoningEffort: { const: "MEDIUM" },
				maxOutputTokens: { const: 32_768 },
				endpointSha256: { type: "string", pattern: "^[0-9a-f]{64}$" },
			},
		},
		...Object.fromEntries(
			["compositions", "occasions"].map((key) => [
				key,
				{
					type: "array",
					items: {
						type: "object",
						required: ["id", "status"],
						properties: { id: { type: "string" }, status: { type: ["integer", "null"] } },
					},
				},
			]),
		),
	},
});
const existingExecution: unknown = existsSync(executionFile) ? read(executionFile) : null;
if (existingExecution !== null && !validateExecution(existingExecution)) {
	throw new Error("Invalid execution evidence");
}
let execution = validateExecution(existingExecution) ? existingExecution : null;
const required = (name: string) => {
	const value = process.env[name];
	if (value === undefined || value.length === 0) {
		throw new Error(`Missing ${name}`);
	}
	return value;
};
if (!values["score-only"]) {
	for (const name of [
		"HEPHAESTUS_LIVE_LLM_API_KEY",
		"HEPHAESTUS_LIVE_LLM_BASE_URL",
		"HEPHAESTUS_LIVE_LLM_MODEL",
	]) {
		if (process.env[name] === undefined || process.env[name].length === 0) {
			throw new Error(`Missing ${name}`);
		}
	}
	const requested = {
		id: required("HEPHAESTUS_LIVE_LLM_MODEL"),
		reasoningEffort: "MEDIUM",
		maxOutputTokens: 32_768,
		endpointSha256: createHash("sha256")
			.update(required("HEPHAESTUS_LIVE_LLM_BASE_URL").replace(/\/+$/u, ""))
			.digest("hex"),
	};
	if (
		execution !== null &&
		(execution.commit !== commit || JSON.stringify(execution.model) !== JSON.stringify(requested))
	) {
		throw new Error("Use a new output directory for another commit, model or endpoint");
	}
	if (execution === null && existsSync(checked)) {
		throw new Error("Existing compositions have no execution evidence; use a new output directory");
	}
	execution = { version: 1, commit, model: requested, compositions: [], occasions: [] };
	const saveExecution = () =>
		writeFileSync(executionFile, `${JSON.stringify(execution, null, 2)}\n`);
	saveExecution();
	for (const caseId of [...set.cases.map((item) => item.id), ...privateExpected]) {
		const result = spawnSync(
			"./gradlew",
			[":application:liveTest", "--tests", "CompositionEvalLiveTest", "--console=plain"],
			{
				cwd: "server",
				stdio: "inherit",
				env: { ...process.env, COMPOSITION_EVAL_CHECKED: checked, COMPOSITION_EVAL_CASES: caseId },
			},
		);
		execution.compositions.push({ id: caseId, status: result.status });
		saveExecution();
	}

	writeFileSync(occasionFile, JSON.stringify({ version: 1, commit, model: requested, cases: [] }));
	for (const caseId of occasionPanel.cases) {
		const result = spawnSync(
			"./gradlew",
			[
				":application:liveTest",
				"--console=plain",
				`-PcriteriaCases=${caseId}`,
				"-PcriteriaReasoningEffort=MEDIUM",
				"-PcriteriaMaxOutputTokens=32768",
			],
			{
				cwd: "server",
				stdio: "inherit",
				env: {
					...process.env,
					RELEASE_FEEDBACK_EVAL_OCCASIONS: occasionFile,
					PI_RUNNER_CWD: path.resolve(output, "occasion-workspace"),
				},
			},
		);
		execution.occasions.push({ id: caseId, status: result.status });
		saveExecution();
	}
}
const run: RunFile = existsSync(checked)
	? parseRun(read(checked), checked)
	: {
			version: 1 as const,
			commit,
			model: { id: process.env.HEPHAESTUS_LIVE_LLM_MODEL ?? null },
			runner: null,
			cases: [],
		};
const validateModel = new Ajv2020().compile<{
	id: string;
	reasoningEffort: string;
	maxOutputTokens: number;
	endpointSha256: string;
}>({
	type: "object",
	required: ["id", "reasoningEffort", "maxOutputTokens", "endpointSha256"],
	properties: {
		id: { type: "string", minLength: 1 },
		reasoningEffort: { type: "string", minLength: 1 },
		maxOutputTokens: { type: "integer", minimum: 1 },
		endpointSha256: { type: "string", pattern: "^[0-9a-f]{64}$" },
	},
});
const { model } = run;
const modelKnown = validateModel(model);
const runSha256 = existsSync(checked) ? digest(checked) : null;
const labels =
	values.labels === undefined ? undefined : parseRunLabels(read(values.labels), values.labels);
const publicLabels = labels
	? { ...labels, labels: labels.labels.filter((label) => publicIds.has(label.caseId)) }
	: undefined;
const report = compositionReport(set, undefined, [{ name: "release", run, labels: publicLabels }]);
const summary = report.runs[0];
if (!summary) {
	throw new Error("No composition measurement");
}
const ids = new Set(set.cases.map((item) => item.id));
const outputs = new Map(run.cases.map((item) => [item.id, item]));
const complete =
	outputs.size === run.cases.length &&
	outputs.size === ids.size &&
	[...outputs.keys()].every((id) => ids.has(id));
const rubricSha256 = digest("docs/contributor/composition-label-rubric.md");
const labelBinding =
	runSha256 !== null &&
	labels?.runSha256 === runSha256 &&
	labels.source === "ai-drafted" &&
	labels.labeller?.rubricSha256 === rubricSha256 &&
	labels.labeller.model !== (modelKnown ? model.id : null) &&
	![...run.cases, ...(run.privateCases ?? [])].some(
		(row) => row.servedModels?.includes(labels.labeller?.model ?? "") === true,
	);
const completed = (entry: { accepted: boolean; failure?: string | null } | undefined) =>
	entry?.accepted === true && (entry.failure === undefined || entry.failure === null);
const rate = (successes: number, total: number) => ({
	successes,
	total,
	estimate: total ? successes / total : null,
	interval95: wilson95(successes, total),
});
const trust = publicLabels?.labels.filter((label) =>
	label.codes.some((code) => code.startsWith("T")),
).length;
const labelErrors = (labels?.labels ?? []).flatMap((label) => {
	const entry = outputs.get(label.caseId);
	const missing =
		entry !== undefined && completed(entry) && entry.review !== null
			? mechanicalCodes(entry.review).filter((code) => !label.codes.includes(code))
			: [];

	return missing.length > 0
		? [`${label.caseId}: label omits mechanical codes ${missing.join(", ")}`]
		: [];
});
const privateOutputs = run.privateCases ?? [];
const privateReview = new Ajv2020().compile<{
	feedback: {
		destination: string;
		practiceSlug: string;
		action: string;
		body?: string;
		nextStep?: string;
		notes?: Record<string, string>;
	}[];
}>({
	type: "object",
	required: ["feedback"],
	properties: {
		feedback: {
			type: "array",
			minItems: 1,
			items: {
				type: "object",
				required: ["destination", "practiceSlug", "action"],
				properties: {
					destination: { type: "string" },
					practiceSlug: { type: "string" },
					action: { enum: ["NEW", "SUPERSEDE", "WITHHOLD"] },
					body: { type: "string" },
					nextStep: { type: "string" },
					notes: { type: "object", additionalProperties: { type: "string" } },
				},
			},
		},
	},
});
const privateCorrect = (id: string) => {
	const entry = privateOutputs.find((row) => row.id === id);
	if (!completed(entry) || !privateReview(entry?.review)) {
		return false;
	}
	const [source, destination] = id.split("/");
	const expected =
		set.cases.find((row) => row.id === source)?.results.map((row) => row.practice) ?? [];
	const pieces = entry.review.feedback;
	return (
		pieces.length === expected.length &&
		expected.every(
			(slug) =>
				pieces.filter(
					(piece) =>
						piece.practiceSlug === slug &&
						piece.destination === destination &&
						piece.action === panel.expectedActions[id],
				).length === 1,
		)
	);
};
for (const entry of privateOutputs) {
	if (!privateReview(entry.review)) {
		continue;
	}
	const texts = entry.review.feedback.flatMap((piece) => [
		...(piece.body === undefined ? [] : [{ body: piece.body, target: 600 }]),
		...(piece.nextStep === undefined ? [] : [{ body: piece.nextStep, target: 300 }]),
		...Object.entries(piece.notes ?? {}).map(([field, body]) => ({
			body,
			target: field === "evidenceSummary" ? 600 : 300,
		})),
	]);
	const codes = mechanicalTextCodes(texts);
	const label = labels?.labels.find((row) => row.caseId === entry.id);
	const omitted = codes.filter((code) => label?.codes.includes(code) !== true);
	if (omitted.length > 0) {
		labelErrors.push(`${entry.id}: label omits mechanical codes ${omitted.join(", ")}`);
	}
}
const privateById = new Map(privateOutputs.map((entry) => [entry.id, entry]));
const privateLabels =
	labels?.labels.filter((label) => privateExpected.includes(label.caseId)) ?? [];
const labelsById = new Map(labels?.labels.map((label) => [label.caseId, label]));
const privateComplete =
	privateById.size === privateOutputs.length &&
	privateExpected.length === privateOutputs.length &&
	privateExpected.every((id) => privateById.has(id));
const privateProblems = [
	...privateExpected
		.filter((id) => !privateCorrect(id))
		.map((id) => `${id}: private action differs from its expected decision`),
	...(privateComplete ? [] : ["Missing, repeated or unknown private composition cases"]),
	...(labels &&
	(labelsById.size !== labels.labels.length ||
		labels.labels.some(
			(label) => !publicIds.has(label.caseId) && !privateExpected.includes(label.caseId),
		))
		? ["Repeated or unknown output labels"]
		: []),
	...privateExpected.flatMap((id) => {
		const entry = privateById.get(id);
		const label = labelsById.get(id);
		const findings: string[] = [];
		if (!completed(entry)) {
			findings.push(`${id}: private composition not accepted`);
		}
		if (label === undefined) {
			findings.push(`${id}: missing private composition label`);
		} else if (!label.good) {
			findings.push(`${id}: ${label.codes.join(", ")}: ${label.why}`);
		}
		return findings;
	}),
];
const privateRates = panel.destinations.map((destination) => {
	const expected = privateExpected.filter((id) => id.includes(`/${destination}/`));
	const rated = privateLabels.filter((label) => expected.includes(label.caseId));
	const completeLabels =
		privateComplete &&
		labelErrors.length === 0 &&
		labelBinding &&
		rated.length === expected.length &&
		labelsById.size === labels.labels.length &&
		expected.every((id) => labelsById.has(id));
	return {
		destination,
		decisionsCorrect: rate(expected.filter(privateCorrect).length, expected.length),
		accepted: rate(expected.filter((id) => completed(privateById.get(id))).length, expected.length),
		good: completeLabels
			? rate(
					rated.filter((label) => label.good && completed(privateById.get(label.caseId))).length,
					expected.length,
				)
			: null,
		trustFailure: completeLabels
			? rate(
					rated.filter((label) => label.codes.some((code) => code.startsWith("T"))).length,
					expected.length,
				)
			: null,
	};
});
const validateOccasions = new Ajv2020().compile<{
	version: 1;
	commit: string;
	model?: unknown;
	cases: {
		id: string;
		runnerModel?: string;
		actual: Record<string, string>;
		actualSeverity: Record<string, string>;
	}[];
}>({
	type: "object",
	required: ["version", "commit", "cases"],
	properties: {
		version: { const: 1 },
		commit: { type: "string" },
		model: {},
		cases: {
			type: "array",
			items: {
				type: "object",
				required: ["id", "actual", "actualSeverity"],
				properties: {
					id: { type: "string" },
					runnerModel: { type: "string" },
					actual: { type: "object", additionalProperties: { type: "string" } },
					actualSeverity: { type: "object", additionalProperties: { type: "string" } },
				},
			},
		},
	},
});
const occasionData: unknown = existsSync(occasionFile)
	? read(occasionFile)
	: { version: 1, commit, cases: [] };
if (!validateOccasions(occasionData)) {
	throw new Error("Invalid occasion results");
}
const validateCriteria = new Ajv2020().compile<
	{ id: string; expected: Record<string, string>; expectedSeverity?: Record<string, string> }[]
>({
	type: "array",
	items: {
		type: "object",
		required: ["id", "expected"],
		properties: {
			id: { type: "string" },
			expected: { type: "object", additionalProperties: { type: "string" } },
			expectedSeverity: { type: "object", additionalProperties: { type: "string" } },
		},
	},
});
const criteria: unknown = read(
	"server/application/src/test/resources/practices/criteria-cases.json",
);
if (!validateCriteria(criteria)) {
	throw new Error("Invalid criteria cases");
}
const occasionById = new Map(occasionData.cases.map((row) => [row.id, row]));
const same = (left: Record<string, string>, right: Record<string, string>) =>
	Object.keys(left).length === Object.keys(right).length &&
	Object.entries(left).every(([key, value]) => right[key] === value);
const occasionProblems = [
	...(new Set(
		occasionData.cases.map((row) => row.runnerModel).filter((identity) => identity !== undefined),
	).size === 1
		? []
		: ["Occasion rows have missing or mixed runner models"]),
	...(execution !== null && JSON.stringify(occasionData.model) === JSON.stringify(execution.model)
		? []
		: ["Missing or mismatched occasion model evidence"]),
	...occasionData.cases
		.filter((row) => row.runnerModel === undefined || row.runnerModel.length === 0)
		.map((row) => `${row.id}: missing runner model identity`),
	...(occasionData.commit === commit ? [] : ["Occasion results belong to another commit"]),
	...(occasionById.size !== occasionData.cases.length ||
	occasionPanel.cases.length !== occasionData.cases.length ||
	occasionData.cases.some((row) => !occasionPanel.cases.includes(row.id))
		? ["Missing, repeated or unknown occasion cases"]
		: []),
	...occasionPanel.cases.flatMap((id) => {
		const expected = criteria.find((row) => row.id === id);
		const actual = occasionById.get(id);
		if (actual === undefined) {
			return [`${id}: no assessment result; execution did not complete`];
		}
		return expected &&
			same(expected.expected, actual.actual) &&
			same(expected.expectedSeverity ?? {}, actual.actualSeverity)
			? []
			: [`${id}: occasion outcome or severity differs from its expected assessment`];
	}),
];
const executionProblems = (key: "compositions" | "occasions", expected: string[]) => {
	const rows = execution?.[key] ?? [];
	return rows.length === expected.length &&
		new Set(rows.map((row) => row.id)).size === expected.length &&
		rows.every((row) => expected.includes(row.id) && row.status === 0)
		? []
		: [`${key}: missing or failed live execution evidence`];
};
const problems = [
	...[...run.cases, ...privateOutputs]
		.filter(
			(row) => completed(row) && (row.servedModels === undefined || row.servedModels.length !== 1),
		)
		.map((row) => `${row.id}: missing or mixed served model identities`),
	...(new Set([...run.cases, ...privateOutputs].flatMap((row) => row.servedModels ?? [])).size === 1
		? []
		: ["Composition responses used different served models"]),
	...(execution?.commit === commit ? [] : ["Missing or mismatched execution commit"]),
	...(execution !== null &&
	modelKnown &&
	execution.model.id === model.id &&
	execution.model.reasoningEffort === model.reasoningEffort &&
	execution.model.maxOutputTokens === model.maxOutputTokens &&
	execution.model.endpointSha256 === model.endpointSha256
		? []
		: ["Composition model differs from execution evidence"]),
	...executionProblems("compositions", [...publicIds, ...privateExpected]),
	...executionProblems("occasions", occasionPanel.cases),
	...(modelKnown ? [] : ["Missing live model identity or settings"]),
	...occasionProblems,
	...privateProblems,
	...labelErrors,
	...(run.commit === commit ? [] : ["Run commit does not match the checked-out commit"]),

	...(complete ? [] : ["Missing, repeated or unknown composition cases"]),
	...(labelBinding
		? []
		: [
				"AI-drafted labels must bind to the output and rubric SHA-256 and name an independent labeller",
			]),
	...((summary.quality?.unlabelled.length ?? 0) > 0 || labels === undefined
		? ["Not every composed review has a label"]
		: []),
	...summary.failures.map((failure) => `${failure.caseId}: ${failure.reason}`),
	...set.cases.flatMap((item) =>
		Object.entries(item.expected)
			.filter(([id]) => !(item.unsettled ?? []).includes(id))
			.flatMap(([id, expected]) => {
				const got = composedDecisions(outputs.get(item.id)?.review).get(id);
				return got === expected
					? []
					: [`${item.id}/${id}: expected ${expected}, got ${got ?? "no decision"}`];
			}),
	),
	...(summary.quality?.bad.map(
		(label) => `${label.caseId}: ${label.codes.join(", ")}: ${label.why}`,
	) ?? []),
];
if (
	execFileSync("git", ["status", "--porcelain", "--untracked-files=all"], {
		encoding: "utf8",
		env: environmentWithoutGitRepository(),
		maxBuffer: CAPTURE_LIMIT_BYTES,
	}).trim()
) {
	problems.push("Source changed during the measurement");
}
const result = {
	version: 1,
	commit,
	createdAt: new Date().toISOString(),
	model: run.model,
	execution,
	runSha256,
	corpusSha256: digest("scripts/feedback-eval/compositions.json"),
	privatePanelSha256: digest("scripts/feedback-eval/private-cases.json"),
	occasionPanelSha256: digest("scripts/feedback-eval/occasion-cases.json"),
	occasionResultsSha256: existsSync(occasionFile) ? digest(occasionFile) : null,
	labels: {
		source: labels?.source ?? "missing",
		labeller: labels?.labeller ?? null,
		runSha256: labels?.runSha256 ?? null,
		sha256: values.labels === undefined ? null : digest(values.labels),
	},
	status: problems.length > 0 ? "findings" : "no-findings-on-panel",
	coverage: {
		measured: [
			"on-work/github",
			"on-work/gitlab",
			"practice-page-composition",
			"conversation-notes",
			"assessment-occasion",
		],
		unmeasured: ["conversation-app-answer", "conversation-slack-answer", "provider-delivery"],
	},
	intervalMethod:
		"Wilson 95% binomial reference intervals on this fixed synthetic panel with AI-drafted labels. Cases are selected and may be correlated; these are not deployment confidence bounds or a release approval.",
	rates: {
		accepted: rate(
			set.cases.filter((item) => completed(outputs.get(item.id))).length,
			set.cases.length,
		),
		good:
			complete &&
			labelBinding &&
			labelErrors.length === 0 &&
			summary.quality?.unlabelled.length === 0
				? rate(
						publicLabels?.labels.filter(
							(label) => label.good && completed(outputs.get(label.caseId)),
						).length ?? 0,
						set.cases.length,
					)
				: null,
		trustFailure:
			complete &&
			labelBinding &&
			labelErrors.length === 0 &&
			summary.quality?.unlabelled.length === 0 &&
			trust !== undefined
				? rate(trust, set.cases.length)
				: null,
		decisionsCorrect: rate(
			set.cases.filter((item) => {
				const got = outputs.get(item.id);
				return (
					got !== undefined &&
					completed(got) &&
					Object.entries(item.expected)
						.filter(([id]) => !(item.unsettled ?? []).includes(id))
						.every(([id, expected]) => composedDecisions(got.review).get(id) === expected)
				);
			}).length,
			set.cases.length,
		),
	},
	report,
	privateRates,
	occasions: {
		source: "ai-drafted",
		model: occasionData.model ?? null,
		cases: occasionData.cases,
		completed: rate(
			occasionData.cases.filter((row) => occasionPanel.cases.includes(row.id)).length,
			occasionPanel.cases.length,
		),
		missing: occasionPanel.cases.filter((id) => !occasionById.has(id)),
		correct: rate(
			occasionData.cases.filter(
				(row) =>
					occasionPanel.cases.includes(row.id) &&
					!occasionProblems.some((reason) => reason.startsWith(`${row.id}:`)),
			).length,
			occasionData.cases.filter((row) => occasionPanel.cases.includes(row.id)).length,
		),
	},
	problems,
};
writeFileSync(path.resolve(output, "results.json"), `${JSON.stringify(result, null, 2)}\n`);
process.stdout.write(
	`Release feedback eval: ${result.status}; evidence: ${path.resolve(output, "results.json")}\n`,
);
process.exitCode = problems.length > 0 ? 1 : 0;
