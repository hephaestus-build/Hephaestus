import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

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
	},
});
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
