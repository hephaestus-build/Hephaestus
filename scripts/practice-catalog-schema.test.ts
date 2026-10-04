import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import Ajv2020 from "ajv/dist/2020.js";

import { asArray, asRecord, parseJson } from "./lib/json.ts";

const resource = "../server/application/src/main/resources/practices/";
const schema = asRecord(
	parseJson(
		readFileSync(new URL(`${resource}default-catalog.schema.json`, import.meta.url), "utf8"),
	),
	"catalogue schema",
);
const catalogue = asRecord(
	parseJson(readFileSync(new URL(`${resource}default-catalog.json`, import.meta.url), "utf8")),
	"catalogue",
);
const validate = new Ajv2020({ strict: true, allErrors: true }).compile(schema);

function withPractice(change: (practice: Record<string, unknown>) => void) {
	const copy = structuredClone(catalogue);
	const group = asRecord(asArray(copy.groups, "groups")[0], "group");
	const practice = asRecord(asArray(group.practices, "practices")[0], "practice");
	change(practice);
	return copy;
}

function judgmentOf(practice: Record<string, unknown>) {
	return asRecord(practice.judgment, "judgment");
}

await test("all shipped practices use the flat occasion schema", () => {
	assert.equal(validate(catalogue), true, JSON.stringify(validate.errors));
});

for (const [name, change] of [
	[
		"the removed occasion array",
		(p) => {
			p.on = [{ signals: p.signals }];
		},
	],
	[
		"a string in place of a signal list",
		(p) => {
			p.signals = "scm.pull_request.opened";
		},
	],
	[
		"an empty signal list",
		(p) => {
			p.signals = [];
		},
	],
	[
		"duplicate signals",
		(p) => {
			p.signals = ["scm.pull_request.opened", "scm.pull_request.opened"];
		},
	],
	[
		"an explicit empty evidence list",
		(p) => {
			p.evidenceRequirements = [];
		},
	],
	[
		"an invalid evidence stance",
		(p) => {
			p.evidenceRequirements = [{ sourceKind: "scm.pull-request.diff", stance: "OPTIONAL" }];
		},
	],
	[
		"a misspelled evidence field",
		(p) => {
			p.needs = [];
		},
	],
	[
		"a string draft flag",
		(p) => {
			p.onDrafts = "true";
		},
	],
	[
		"an invalid subject",
		(p) => {
			p.subject = "OWNER";
		},
	],
	[
		"the removed gate field",
		(p) => {
			p.appliesWhen = {
				absentSays: "No Swift code",
				anyOf: [{ changedPathMatches: ["**/*.swift"] }],
			};
		},
	],
	[
		"a gate with no alternatives",
		(p) => {
			p.precondition = { absentSays: "No Swift code", anyOf: [] };
		},
	],
	[
		"a gate clause with two predicates",
		(p) => {
			p.precondition = {
				absentSays: "No Swift code",
				anyOf: [{ changedPathMatches: ["**/*.swift"], diffContains: ["View"] }],
			};
		},
	],
	[
		"a practice without a judgment",
		(p) => {
			delete p.judgment;
		},
	],
	[
		"a judgment without questions",
		(p) => {
			p.judgment = { questions: [], rules: judgmentOf(p).rules };
		},
	],
	[
		"a rule that requires an undetermined answer",
		(p) => {
			const judgment = judgmentOf(p);
			const { key } = asRecord(asArray(judgment.questions, "questions")[0], "question");
			asRecord(asArray(judgment.rules, "rules")[0], "rule").when = {
				[String(key)]: "UNDETERMINED",
			};
		},
	],
	[
		"a not met rule without a severity",
		(p) => {
			const rule = asArray(judgmentOf(p).rules, "rules")
				.map((value) => asRecord(value, "rule"))
				.find((value) => value.outcome === "NOT_MET");
			delete rule?.severity;
		},
	],
	[
		"a met rule with a severity",
		(p) => {
			asRecord(asArray(judgmentOf(p).rules, "rules").at(-1), "rule").severity = "MINOR";
		},
	],
	[
		"the removed informational severity",
		(p) => {
			const rule = asArray(judgmentOf(p).rules, "rules")
				.map((value) => asRecord(value, "rule"))
				.find((value) => value.outcome === "NOT_MET");
			if (rule) {
				rule.severity = "INFO";
			}
		},
	],
	[
		"a rule without a reason",
		(p) => {
			delete asRecord(asArray(judgmentOf(p).rules, "rules")[0], "rule").reason;
		},
	],
	[
		"a question title with a final period",
		(p) => {
			asRecord(asArray(judgmentOf(p).questions, "questions")[0], "question").title =
				"Title names the change.";
		},
	],
] satisfies [string, (practice: Record<string, unknown>) => void][]) {
	await test(`the catalogue rejects ${name}`, () => {
		assert.equal(validate(withPractice(change)), false);
	});
}

await test("the schema permits omitted optional occasion fields", () => {
	const value = withPractice((p) => {
		delete p.evidenceRequirements;
		delete p.subject;
		delete p.onDrafts;
		delete p.precondition;
	});
	assert.equal(validate(value), true, JSON.stringify(validate.errors));
});

await test("every bundled practice asks its questions, reviewed or not", () => {
	for (const group of asArray(catalogue.groups, "groups")) {
		for (const value of asArray(asRecord(group, "group").practices, "practices")) {
			const practice = asRecord(value, "practice");
			assert.notEqual(practice.judgment, undefined, `${String(practice.slug)}: no judgment`);
		}
	}
});
