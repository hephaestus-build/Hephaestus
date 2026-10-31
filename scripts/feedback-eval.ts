import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import {
	compositionReport,
	parseCompositions,
	parseGate,
	parseRun,
	parseRunLabels,
	type RunLabels,
} from "./feedback-eval/compositions.ts";
import {
	blindCase,
	caseHash,
	parseCorpus,
	parseHumans,
	parseJudge,
	sha256,
} from "./feedback-eval/model.ts";
import { report } from "./feedback-eval/score.ts";
import { parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		corpus: { type: "string" },
		labels: { type: "string" },
		judge: { type: "string" },
		requests: { type: "boolean", default: false },
		compositions: { type: "string" },
		gate: { type: "string" },
		run: { type: "string", multiple: true },
		"run-labels": { type: "string", multiple: true },
	},
});
const read = (file: string): unknown => parseJson(readFileSync(file, "utf8"));
/** `name=file` pairs, so that runs and their labels line up by name. */
const named = (pairs: string[] | undefined) =>
	new Map(
		(pairs ?? []).map((pair) => {
			const at = pair.indexOf("=");
			if (at < 1) {
				throw new Error(`Expected name=file, got ${pair}`);
			}
			return [pair.slice(0, at), pair.slice(at + 1)];
		}),
	);
if (values.compositions !== undefined) {
	const set = parseCompositions(read(values.compositions));
	const labels = named(values["run-labels"]);
	const runs = [...named(values.run)].map(([name, file]) => {
		const labelFile = labels.get(name);
		const runLabels: RunLabels | undefined =
			labelFile === undefined ? undefined : parseRunLabels(read(labelFile), labelFile);
		return { name, run: parseRun(read(file), file), labels: runLabels };
	});
	const gate = values.gate === undefined ? undefined : parseGate(read(values.gate), values.gate);
	process.stdout.write(`${JSON.stringify(compositionReport(set, gate, runs), null, 2)}\n`);
	process.exit(0);
}
const corpus = parseCorpus(
	parseJson(
		readFileSync(values.corpus ?? new URL("feedback-eval/cases.json", import.meta.url), "utf8"),
	),
);
const rubric = readFileSync(new URL("feedback-eval/rubric.md", import.meta.url), "utf8");
if (values.requests) {
	if (values.labels !== undefined || values.judge !== undefined) {
		throw new Error("Request export cannot include labels or judge results");
	}
	process.stdout.write(
		`${JSON.stringify(
			{
				version: 1,
				rubricHash: sha256(rubric),
				rubric,
				requests: corpus.cases.map((item) => ({
					caseHash: caseHash(item),
					...blindCase(item),
				})),
			},
			null,
			2,
		)}\n`,
	);
} else {
	const humans =
		values.labels === undefined
			? undefined
			: parseHumans(parseJson(readFileSync(values.labels, "utf8")), corpus);
	const judge =
		values.judge === undefined
			? undefined
			: parseJudge(parseJson(readFileSync(values.judge, "utf8")), corpus, rubric);
	process.stdout.write(`${JSON.stringify(report(corpus, humans, judge), null, 2)}\n`);
}
