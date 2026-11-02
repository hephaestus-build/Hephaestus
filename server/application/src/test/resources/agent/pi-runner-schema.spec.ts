import assert from "node:assert/strict";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";

import {
	capturedLines,
	field,
	readJsonFile,
	runRunner,
	SESSION_CANDIDATE,
	stagePractices,
} from "./program-host-runner.ts";

const citation = { path: "evidence/metadata.json", startLine: 1, quote: "Add login" };
const problem = {
	severity: "MAJOR",
	summary: "Authentication call is insecure",
	explanation: "The description names the insecure call.",
	citations: [citation],
	basis: {
		type: "duty",
		event: 0,
		criterionQuote: "Added authentication calls use checked(), not insecure().",
		explanation: "The event adds authentication.",
	},
};
const negative = { ...SESSION_CANDIDATE, citations: [], problems: [problem], evidence: {} };
const uncertain = {
	outcome: "COULD_NOT_DETERMINE",
	summary: "Authentication check remains unclear",
	explanation: "The description does not settle whether the call is checked.",
	citations: [citation],
	evidence: {
		search: {
			consulted: ["scm.pull-request.diff"],
			lookedFor: "checked authentication call",
			boundary: "Captured change only",
		},
		openQuestions: [
			{
				openQuestion: "Is the call checked?",
				wouldSettleIt: "The implementation of the named authentication call.",
			},
		],
	},
};
const inapplicable = {
	...uncertain,
	outcome: "DOES_NOT_APPLY",
	summary: "No interface changes are described",
	evidence: {
		inapplicability: {
			consulted: ["scm.pull-request.core", "scm.pull-request.diff"],
			prerequisite: "UI changes",
			ruledOutBy: "The description names a server call.",
		},
	},
};

async function replay(sent: unknown, corrected?: unknown, itemKind?: string) {
	const cwd = mkdtempSync(nodePath.join(tmpdir(), "pi-schema-replay-"));
	stagePractices(cwd, [{ slug: "test-practice", itemKind }]);
	try {
		const exit = await runRunner(
			cwd,
			{},
			{
				PI_HOST_SESSION_CANDIDATE: JSON.stringify(sent),
				...(corrected === undefined
					? {}
					: { PI_HOST_SESSION_CORRECTION: JSON.stringify(corrected) }),
			},
		);
		assert.equal(exit.status, 0, exit.stderr);
		const stored = existsSync(nodePath.join(cwd, "out/result.json"))
			? field(readJsonFile(cwd, "out/result.json"), "results")
			: [];
		assert.ok(Array.isArray(stored) && stored.length === 1, exit.stderr);
		return capturedLines(cwd, "submitted").map((entry) => JSON.stringify(field(entry, "reply")));
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
}

for (const [name, sent] of [
	...[null, [], "", {}].map(
		(empty) =>
			[
				`unused MET evidence ${JSON.stringify(empty)}`,
				{
					...uncertain,
					outcome: "met",
					evidence: { inapplicability: empty, openQuestions: empty },
				},
			] as const,
	),
	["lowercase negative outcome", { ...negative, outcome: "not_met" }],
	["mixed-case uncertain outcome", { ...uncertain, outcome: "Could_Not_Determine" }],
	["undetermined alias", { ...uncertain, outcome: "UNDETERMINED" }],
	["not applicable alias", { ...inapplicable, outcome: "not_applicable" }],
	...[null, [], "", {}].flatMap((empty) => [
		[
			`unused negative evidence ${JSON.stringify(empty)}`,
			{ ...negative, evidence: { inapplicability: empty } },
		] as const,
		[
			`unused inapplicable evidence ${JSON.stringify(empty)}`,
			{
				...inapplicable,
				evidence: { ...inapplicable.evidence, search: empty, openQuestions: empty },
			},
		] as const,
	]),
	["omitted problems", uncertain],
	["null problems", { ...uncertain, problems: null }],
	["empty problems", { ...uncertain, problems: [] }],
	["inapplicable null problems", { ...inapplicable, problems: null }],
	...[null, [], "", {}].map(
		(empty) =>
			[
				`unused uncertain result fields ${JSON.stringify(empty)}`,
				{ ...uncertain, problems: empty, example: empty },
			] as const,
	),
	...["args", "arguments", "input"].map(
		(wrapper) => [`one ${wrapper} wrapper`, { [wrapper]: negative }] as const,
	),
] as const) {
	void test(`submit_result stores ${name} on the first try`, async () => {
		const replies = await replay(sent);
		assert.equal(replies.length, 1);
		assert.match(replies[0] ?? "", /stored/u);
	});
}

const without = (value: Record<string, unknown>, key: string) =>
	Object.fromEntries(Object.entries(value).filter(([name]) => name !== key));
for (const [name, sent, corrected, error] of [
	...[null, [], "", {}].flatMap((empty) => [
		[
			`empty inapplicability ${JSON.stringify(empty)}`,
			{ ...inapplicable, evidence: { inapplicability: empty } },
			inapplicable,
			/Required: inapplicability.*Corrected call example/iu,
		] as const,
		[
			`empty questions ${JSON.stringify(empty)}`,
			{ ...uncertain, evidence: { openQuestions: empty } },
			uncertain,
			/Required: openQuestions.*Corrected call example/iu,
		] as const,
	]),
	[
		"missing inapplicability",
		{ ...inapplicable, evidence: {} },
		inapplicable,
		/Required: inapplicability.*Corrected call example/iu,
	],
	[
		"missing open questions",
		{ ...uncertain, evidence: {} },
		uncertain,
		/Required: openQuestions.*Corrected call example/iu,
	],
	[
		"empty open questions",
		{ ...uncertain, evidence: { openQuestions: [] } },
		uncertain,
		/Required: openQuestions.*Corrected call example/iu,
	],
	[
		"ambiguous PASS outcome",
		{ ...negative, outcome: "PASS" },
		negative,
		/outcome.*expected.*no outcome was inferred/iu,
	],
	[
		"ambiguous FAIL outcome",
		{ ...negative, outcome: "FAIL" },
		negative,
		/outcome.*expected.*no outcome was inferred/iu,
	],
	[
		"nonempty unused evidence",
		{ ...negative, evidence: inapplicable.evidence },
		negative,
		/evidence.inapplicability.*only search, openQuestions/iu,
	],
	[
		"string evidence",
		{ ...uncertain, evidence: "The description leaves this unclear." },
		uncertain,
		/evidence.*expected an evidence object.*COULD_NOT_DETERMINE.*openQuestions/iu,
	],
	[
		"invented evidence fields",
		{
			...uncertain,
			evidence: {
				consulted: ["scm.pull-request.core"],
				lookedFor: "checked call",
				boundary: "Captured description only",
			},
		},
		uncertain,
		/evidence.consulted.*only search, openQuestions[\s\S]*Captured description only/iu,
	],
	[
		"missing duty event",
		{ ...negative, problems: [{ ...problem, basis: without(problem.basis, "event") }] },
		negative,
		/problems\.0\.basis\.event.*expected.*integer/iu,
	],
	[
		"unknown basis field",
		{ ...negative, problems: [{ ...problem, basis: { ...problem.basis, observed: true } }] },
		negative,
		/problems\.0\.basis.*observed.*only/iu,
	],
	[
		"missing quote",
		{ ...negative, problems: [{ ...problem, citations: [without(citation, "quote")] }] },
		negative,
		/problems\.0\.citations\.0\.quote.*expected.*string/iu,
	],
	[
		"missing explanation",
		{ ...negative, problems: [without(problem, "explanation")] },
		negative,
		/problems\.0\.explanation.*expected.*string/iu,
	],
	["missing outcome", without(negative, "outcome"), negative, /outcome.*expected/iu],
	["null negative problems", { ...negative, problems: null }, negative, /problem/iu],
	["empty negative problems", { ...negative, problems: [] }, negative, /problem/iu],
	[
		"unsupported state basis",
		{
			...negative,
			problems: [{ ...problem, basis: { type: "state", explanation: "It is present." } }],
		},
		negative,
		/state_basis_not_allowed/iu,
	],
	[
		"wrong record quote",
		{
			...negative,
			problems: [
				{ ...problem, citations: [{ ...citation, quote: "This text was never captured" }] },
			],
		},
		negative,
		/citation does not match/iu,
	],
	[
		"wrong record span",
		{
			...negative,
			problems: [
				{
					...problem,
					citations: [{ ...citation, startLine: 1, endLine: 2, quote: "Add login\nnot captured" }],
				},
			],
		},
		negative,
		/citation does not match/iu,
	],
] as const) {
	void test(`submit_result explains ${name}, then stores the corrected call`, async () => {
		const replies = await replay(sent, corrected);
		assert.equal(replies.length, 2);
		assert.match(replies[0] ?? "", error);
		assert.match(replies[0] ?? "", /Correct(?:ed|ion).*example/iu);
		assert.doesNotMatch(replies[0] ?? "", /; also:|schema is false/iu);
		assert.match(replies[1] ?? "", /stored/u);
	});
}

void test("submit_result explains a state basis on an issue, then stores the duty basis", async () => {
	const replies = await replay(
		{
			...negative,
			problems: [{ ...problem, basis: { type: "state", explanation: "It is present." } }],
		},
		{
			...negative,
			problems: [
				{
					...problem,
					basis: {
						type: "duty",
						criterionQuote: "Added authentication calls use checked()",
						event: 0,
						explanation: "The criteria require this.",
					},
				},
			],
		},
		"scm.issue",
	);
	assert.equal(replies.length, 2);
	assert.match(replies[0] ?? "", /state_basis_not_allowed.*duty/iu);
	assert.match(replies[1] ?? "", /stored/u);
});
