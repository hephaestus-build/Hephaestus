/**
 * Scores a product feedback routing run against the routing cases.
 *
 * The server's unit tier and live tier each write a report of what the routing code did with every
 * case. A leak, a forbidden write, or text that must never reach an issue fails the run.
 *
 *   node scripts/product-feedback-eval.ts --report <report.json> [--cases <cases.json>]
 */
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { parseJson } from "./lib/json.ts";
import { parseCorpus, parseReport, sha256 } from "./product-feedback-eval/model.ts";
import { score } from "./product-feedback-eval/score.ts";

const { values } = parseArgs({
	options: {
		cases: { type: "string" },
		report: { type: "string" },
	},
});
if (values.report === undefined) {
	console.error(
		"Usage: node scripts/product-feedback-eval.ts --report <report.json> [--cases <cases.json>]",
	);
	process.exit(2);
}
const casesText = readFileSync(
	values.cases ?? new URL("product-feedback-eval/cases.json", import.meta.url),
	"utf8",
);
const corpus = parseCorpus(parseJson(casesText));
const report = parseReport(parseJson(readFileSync(values.report, "utf8")));
const result = score(corpus, sha256(casesText), report);

console.log(
	`${report.tier} run, model ${report.model ?? "none"}: ${result.cases} cases, ${result.public} public, ` +
		`${result.privateExpectedPublic} kept private that could have been public.`,
);
for (const finding of result.findings) {
	console.log(`finding: ${finding}`);
}
for (const failure of result.failures) {
	console.error(`FAIL: ${failure}`);
}
process.exitCode = result.failures.length === 0 ? 0 : 1;
