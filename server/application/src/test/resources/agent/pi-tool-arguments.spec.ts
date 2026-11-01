import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { ProblemDrafts } from "../../../main/resources/agent/pi-problem-drafts.ts";
import { normalizeSubmission } from "../../../main/resources/agent/pi-result-normalize.ts";
import { buildResultTools } from "../../../main/resources/agent/pi-result-tools.ts";
import {
	prepareResultArguments,
	validateResultEvidence,
} from "../../../main/resources/agent/pi-tool-arguments.ts";

const changeCitation = {
	sourceKind: "scm.pull-request.diff",
	sourcePath: "context/change.json",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 12,
	quote: "insecure();",
};
const treeCitation = {
	sourceKind: "scm.repository.tree",
	sourcePath: "repos/reviewed/.git/HEAD",
	path: "src/Checked.java",
	commit: "a".repeat(40),
	startLine: 2,
	endLine: 2,
	quote: "checked(token);",
};
const problem = {
	severity: "MAJOR",
	summary: "Login calls insecure()",
	explanation: "The added line calls insecure() where the criteria ask for checked().",
	citations: [changeCitation],
	basis: { type: "introduced", head: 0, explanation: "The change adds the call." },
};
const question = {
	openQuestion: "Does the caller check the token first?",
	wouldSettleIt: "The caller of login().",
	citation: treeCitation,
};
const result = {
	outcome: "NOT_MET",
	summary: "Added authentication call is insecure",
	explanation: "The changed authentication code calls insecure().",
	citations: [treeCitation],
	problems: [problem],
	example: { citation: treeCitation, note: "checked() guards the token here." },
	evidence: { openQuestions: [question] },
};

void test("a result in its declared form is kept as it is, and preparing it again changes nothing", () => {
	const prepared = prepareResultArguments(result);
	assert.deepEqual(prepared, result);
	assert.deepEqual(prepareResultArguments(prepared), prepared);
});

void test("JSON-encoded lists and objects are decoded at every level", () => {
	const encoded = {
		...result,
		citations: JSON.stringify([treeCitation]),
		problems: JSON.stringify([
			{
				...problem,
				citations: JSON.stringify([changeCitation]),
				basis: JSON.stringify(problem.basis),
			},
		]),
		example: JSON.stringify({ ...result.example, citation: JSON.stringify(treeCitation) }),
		evidence: JSON.stringify({ openQuestions: JSON.stringify([question]) }),
	};
	assert.deepEqual(prepareResultArguments(encoded), result);
	assert.equal(typeof encoded.evidence, "string", "the input is not changed");
});

void test("one object sent for a list is a list of it", () => {
	assert.deepEqual(
		prepareResultArguments({
			...result,
			citations: treeCitation,
			problems: { ...problem, citations: changeCitation },
			evidence: { openQuestions: question },
		}),
		result,
	);
});

void test("line coordinates copied from a numbered view are read as numbers, in every citation", () => {
	for (const startLine of ["10", "L10", "[L10]", " [L10] "]) {
		const numbered = { ...changeCitation, startLine, endLine: "[L12]" };
		const tree = { ...treeCitation, startLine: "[L2]", endLine: "2" };
		assert.deepEqual(
			prepareResultArguments({
				...result,
				citations: [tree],
				problems: [{ ...problem, citations: [numbered] }],
				example: { ...result.example, citation: tree },
				evidence: { openQuestions: [{ ...question, citation: tree }] },
			}),
			result,
			startLine,
		);
	}
});

void test("only decimal line coordinates are read; any other start line is left for validation", () => {
	for (const startLine of [
		"E10",
		"[10]",
		"L10]",
		"[L10",
		"1e1",
		"10.5",
		"-10",
		"10-12",
		"line 10",
		"9007199254740993",
		"[L9007199254740993]",
		null,
	]) {
		const input = { ...result, citations: [{ ...treeCitation, startLine }] };
		assert.deepEqual(prepareResultArguments(input), input, String(startLine));
	}
});

void test("an end line that is no line coordinate is refused", () => {
	assert.throws(
		() => prepareResultArguments({ ...result, citations: [{ ...treeCitation, endLine: "end" }] }),
		/citation endLine must be a line coordinate or null/u,
	);
	assert.deepEqual(
		prepareResultArguments({ ...result, citations: [{ ...treeCitation, endLine: null }] }),
		{ ...result, citations: [{ ...treeCitation, endLine: null }] },
	);
});

void test("the indices of a problem's basis are read from their digits", () => {
	const basis = {
		type: "worsened",
		base: " 1 ",
		head: "0",
		explanation: "The change adds a second call.",
	};
	const duty = {
		type: "duty",
		criterionQuote: "use checked()",
		event: "0",
		explanation: "The duty applies.",
	};
	const [worsened, owed] = [basis, duty].map((sent) => {
		const prepared = prepareResultArguments({ ...result, problems: [{ ...problem, basis: sent }] });
		const problems: unknown = prepared.problems;
		assert.ok(Array.isArray(problems));
		const first: unknown = problems[0];
		assert.ok(typeof first === "object" && first !== null);
		const sentBasis: unknown = Reflect.get(first, "basis");
		return sentBasis;
	});
	assert.deepEqual(worsened, { ...basis, base: 1, head: 0 });
	assert.deepEqual(owed, { ...duty, event: 0 });
	const odd = { type: "introduced", head: "first", explanation: "x" };
	assert.deepEqual(prepareResultArguments({ ...result, problems: [{ ...problem, basis: odd }] }), {
		...result,
		problems: [{ ...problem, basis: odd }],
	});
});

void test("citations sent under evidence are kept, repaired, for the normalizer to move to the result", () => {
	const prepared = prepareResultArguments({
		...result,
		evidence: { citations: JSON.stringify({ ...treeCitation, startLine: "[L2]" }) },
	});
	assert.deepEqual(prepared.evidence, { citations: [treeCitation] });
});

void test("an open question without a citation is kept as it is", () => {
	const unlocated = { openQuestion: "Is login() reachable?", wouldSettleIt: "A caller." };
	assert.deepEqual(
		prepareResultArguments({ ...result, evidence: { openQuestions: [unlocated] } }),
		{ ...result, evidence: { openQuestions: [unlocated] } },
	);
});

void test("a side, commit or quote that is not text is refused, wherever the citation is", () => {
	const places = {
		citation: (cited: object) => ({ ...result, citations: [cited] }),
		"problem citation": (cited: object) => ({
			...result,
			problems: [{ ...problem, citations: [cited] }],
		}),
		"example citation": (cited: object) => ({
			...result,
			example: { ...result.example, citation: cited },
		}),
		"open question citation": (cited: object) => ({
			...result,
			evidence: { openQuestions: [{ ...question, citation: cited }] },
		}),
		"evidence citation": (cited: object) => ({ ...result, evidence: { citations: [cited] } }),
	};
	for (const [where, place] of Object.entries(places)) {
		for (const field of ["side", "commit", "quote"]) {
			assert.throws(
				() => prepareResultArguments(place({ ...changeCitation, [field]: 42 })),
				new RegExp(
					`^Error: ${where} ${field} must be a string or null\\. Corrected field example:`,
					"u",
				),
				`${where} ${field}`,
			);
		}
		for (const field of ["side", "commit"]) {
			assert.throws(
				() => prepareResultArguments(place({ ...changeCitation, [field]: " " })),
				new RegExp(
					`^Error: ${where} ${field} must be nonempty or null\\. Corrected field example:`,
					"u",
				),
				`${where} ${field}`,
			);
		}
	}
	assert.doesNotThrow(() =>
		prepareResultArguments({ ...result, citations: [{ ...treeCitation, quote: "" }] }),
	);
});

void test("a warrant that is not an object is refused; an absent one is not", () => {
	for (const branch of ["search", "inapplicability"]) {
		assert.throws(
			() => prepareResultArguments({ ...result, evidence: { [branch]: "the diff" } }),
			new RegExp(`evidence\\.${branch} must be an object or null`, "u"),
		);
		if (branch === "search") {
			assert.throws(() => prepareResultArguments({ ...result, evidence: { [branch]: [] } }));
		} else {
			assert.deepEqual(
				prepareResultArguments({ ...result, evidence: { [branch]: [] } }).evidence,
				{},
			);
		}
		assert.deepEqual(prepareResultArguments({ ...result, evidence: { [branch]: null } }), {
			...result,
			evidence: branch === "search" ? { [branch]: null } : {},
		});
	}
});

void test("malformed containers and invalid claims are left for validation to refuse", () => {
	for (const evidence of ["{not json}", "null", "[]", "42", [], { openQuestions: "not JSON" }]) {
		const input = { ...result, outcome: "INVALID", evidence };
		assert.deepEqual(prepareResultArguments(input), input, JSON.stringify(evidence));
	}
	for (const citations of ["not JSON", "42", 42]) {
		const input = { ...result, citations };
		assert.deepEqual(prepareResultArguments(input), input, JSON.stringify(citations));
	}
	const invented = {
		...changeCitation,
		path: "../task.json",
		quote: "invented();",
		side: "INVALID",
		endLine: 99_999,
	};
	assert.deepEqual(prepareResultArguments({ ...result, citations: [invented] }), {
		...result,
		citations: [invented],
	});
});

void test("arguments that are not one result object become an empty object, which the required fields refuse", () => {
	for (const args of [null, 42, [result], JSON.stringify(result), JSON.stringify([result])]) {
		assert.deepEqual(prepareResultArguments(args), {});
	}
});

void test("review prompts and bundled practices do not require write or edit tools", () => {
	for (const path of [
		"agent/pi-orchestrator.md",
		"agent/feedback-composer.md",
		"practices/default-catalog.json",
	]) {
		const text = readFileSync(new URL(`../../../main/resources/${path}`, import.meta.url), "utf8");
		assert.doesNotMatch(
			text,
			/`(?:write|edit)`|tools\.(?:write|edit)\(|\b(?:write|edit) tool\b/u,
			path,
		);
	}
});

void test("all outcomes without problems accept null and empty lists without changing evidence", () => {
	for (const outcome of ["MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE"]) {
		const { problems: _problems, ...withoutProblems } = { ...result, outcome };
		for (const problems of [null, [], "", {}]) {
			assert.deepEqual(prepareResultArguments({ ...withoutProblems, problems }), withoutProblems);
		}
	}
});

void test("argument envelopes neither discard sibling fields nor unwrap a second envelope", () => {
	const withSibling = { args: result, confidence: 1 };
	assert.deepEqual(prepareResultArguments(withSibling), withSibling);
	assert.deepEqual(prepareResultArguments({ args: { input: result } }), { input: result });
});

const search = {
	consulted: ["scm.pull-request.diff"],
	lookedFor: "Unchecked authentication calls",
	boundary: "Captured change only",
};
const inapplicability = {
	consulted: ["scm.pull-request.diff"],
	prerequisite: "UI changes",
	ruledOutBy: "Only a server test changed.",
};

void test("one JSON-encoded envelope retains every claim", () => {
	for (const key of ["args", "arguments", "input"]) {
		assert.deepEqual(prepareResultArguments({ [key]: JSON.stringify(result) }), result);
		assert.deepEqual(prepareResultArguments({ [key]: JSON.stringify({ input: result }) }), {
			input: result,
		});
		const sibling = { [key]: JSON.stringify(result), confidence: 1 };
		assert.deepEqual(prepareResultArguments(sibling), sibling);
	}
});

void test("an extra closing brace after complete evidence carries no claim and is removed", () => {
	for (const [outcome, evidence] of [
		["MET", { search }],
		["NOT_MET", { search }],
		["DOES_NOT_APPLY", { inapplicability }],
		["COULD_NOT_DETERMINE", { search, openQuestions: [question] }],
	] as const) {
		const input = { outcome, evidence: `${JSON.stringify(evidence)}}` };
		const prepared = prepareResultArguments(input);
		assert.deepEqual(prepared, { outcome, evidence });
		assert.doesNotThrow(() => validateResultEvidence(prepared, []));
		assert.deepEqual(prepareResultArguments(prepared), prepared);
	}
	const unicode = { ...result, explanation: String.raw`Read "🔒" and escaped \ delimiters: } ]` };
	assert.deepEqual(prepareResultArguments({ args: `${JSON.stringify(unicode)}}` }), unicode);
});

void test("container repair never completes truncated JSON or discards another value", () => {
	for (const evidence of [
		JSON.stringify({ search }).slice(0, -1),
		`${JSON.stringify({ search })} {"inapplicability":{}}`,
		`${JSON.stringify({ search })},"inapplicability":{}}`,
		`${JSON.stringify({ search })}]`,
		'{"search":{"boundary":"unterminated}}}',
	]) {
		const prepared = prepareResultArguments({ outcome: "MET", evidence });
		assert.equal(prepared.evidence, evidence);
		assert.throws(() => validateResultEvidence(prepared, []), /Corrected call example/u);
	}
});

void test("flat search fields need correction, while exact copies of nested fields can be omitted", () => {
	for (const outcome of ["MET", "NOT_MET"]) {
		assert.deepEqual(
			prepareResultArguments({ outcome, evidence: { search, ...search } }).evidence,
			{ search },
		);
		for (const evidence of [
			search,
			{
				search: { consulted: search.consulted },
				lookedFor: search.lookedFor,
				boundary: search.boundary,
			},
			{ search, boundary: "A different search boundary" },
		]) {
			const prepared = prepareResultArguments({ outcome, evidence });
			assert.deepEqual(prepared.evidence, evidence);
			assert.throws(() => validateResultEvidence(prepared, []), /Corrected call example/u);
		}
	}
});

void test("empty or duplicate extras are removed, while different citation coordinates remain invalid", () => {
	for (const unused of [null, [], {}, ""]) {
		assert.deepEqual(
			prepareResultArguments({
				...result,
				citations: [{ ...treeCitation, endLine2: unused }],
				evidence: { openQuestions: [question], inapplicability: unused, spare: unused },
			}),
			result,
		);
	}
	assert.deepEqual(
		prepareResultArguments({
			...result,
			citations: [
				{
					...treeCitation,
					path2: treeCitation.path,
					startLine2: treeCitation.startLine,
					quote2: treeCitation.quote,
				},
			],
		}),
		result,
	);
	const distinct = { ...treeCitation, path2: "src/Other.java", startLine2: 9, quote2: "other();" };
	assert.deepEqual(prepareResultArguments({ ...result, citations: [distinct] }).citations, [
		distinct,
	]);
	assert.deepEqual(
		prepareResultArguments({
			outcome: "DOES_NOT_APPLY",
			evidence: { inapplicability: { ...inapplicability, boundary: "Different content" } },
		}).evidence,
		{ inapplicability: { ...inapplicability, boundary: "Different content" } },
	);
});

void test("a warrant under the wrong outcome key is refused with a correction from the call", () => {
	const input = {
		outcome: "DOES_NOT_APPLY",
		summary: "Server change",
		explanation: "Only the server changed.",
		evidence: { search },
	};
	const prepared = prepareResultArguments(input);
	assert.deepEqual(prepared, input);
	assert.throws(
		() => validateResultEvidence(prepared, []),
		/Required: inapplicability.*Corrected call example.*"summary":"Server change".*"explanation":"Only the server changed\."/u,
	);

	const met = prepareResultArguments({ outcome: "MET", evidence: { openQuestions: [question] } });
	assert.throws(() => validateResultEvidence(met, []), /evidence.openQuestions.*only search/u);
});

void test("missing outcomes and unsupported synonyms remain decisions for the model", () => {
	for (const outcome of [undefined, "PASS", "FAIL", "N/A", { status: "MET" }]) {
		const prepared = prepareResultArguments({
			outcome,
			summary: "Authentication calls",
			evidence: {},
		});
		assert.throws(
			() => validateResultEvidence(prepared, []),
			/Corrected call example.*Authentication calls.*no outcome was inferred/u,
		);
	}
});

void test("string questions require the missing settling evidence and preserve the question in the correction", () => {
	assert.throws(
		() =>
			prepareResultArguments({
				outcome: "COULD_NOT_DETERMINE",
				evidence: { openQuestions: ["Does the caller check the token?"] },
			}),
		/requires openQuestion and wouldSettleIt.*Corrected call example.*"openQuestion":"Does the caller check the token\?"/u,
	);
});

void test("sourceKind is left for captured-source resolution, never inferred from a path spelling", () => {
	const citation = { path: "context/unknown.json", startLine: 1, quote: "captured" };
	assert.deepEqual(prepareResultArguments({ ...result, citations: [citation] }).citations, [
		citation,
	]);
});

void test("null evidence is absence only when the outcome has no required warrant", () => {
	for (const outcome of ["MET", "NOT_MET"]) {
		assert.deepEqual(prepareResultArguments({ outcome, evidence: null }).evidence, {});
	}
	for (const outcome of ["DOES_NOT_APPLY", "COULD_NOT_DETERMINE"]) {
		assert.throws(
			() => validateResultEvidence(prepareResultArguments({ outcome, evidence: null }), []),
			/Required:.*Corrected call example/u,
		);
	}
});

void test("malformed problem strings retain their content for refusal rather than receiving guessed punctuation", () => {
	for (const problems of [
		'[{"summary":"Unchecked call","basis":{"type":"introduced","head":0}]}',
		'[{"summary":"Unchecked call","basis":{"type":"introduced","head":0}}',
		'[{"summary":"Unchecked call","citations":[{"quote":"call();""sourceKind":"scm.pull-request.diff"}]}]',
	]) {
		assert.equal(prepareResultArguments({ ...result, problems }).problems, problems);
	}
	assert.deepEqual(
		prepareResultArguments({ ...result, problems: JSON.stringify([problem]) }),
		result,
	);
});

void test("unused outcomes and duplicate warrant keys cannot erase a required branch", () => {
	const nested = { search: { ...search, openQuestions: [question] }, openQuestions: [question] };
	assert.deepEqual(
		prepareResultArguments({ outcome: "COULD_NOT_DETERMINE", evidence: nested }).evidence,
		nested,
	);
});

void test("malformed JSON envelopes show a complete result shape instead of guessing an outcome", () => {
	for (const wrapper of ["args", "arguments", "input"]) {
		for (const text of [
			JSON.stringify(result).slice(0, -1),
			'"NOT_MET"',
			'{"outcome":"MET" "evidence":{}}',
		]) {
			assert.throws(
				() => prepareResultArguments({ [wrapper]: text }),
				/expected a complete result object.*Corrected call example.*copy your summary.*no content or outcome was inferred/u,
			);
		}
	}
});

/** The registered submit tool and shared Candidate parser, without admitting anything. */
function prepareSubmittedResult(raw: unknown) {
	const drafts = new ProblemDrafts();
	const tool = buildResultTools({
		sourceKinds: ["scm.pull-request.diff", "scm.repository.tree"],
		contextRoot: "context",
		drafts: () => drafts,
		validateProblem: () => {
			throw new Error("This inline submission does not use a draft Problem");
		},
		submit: async () => ({ content: [], details: {} }),
	}).find((entry) => entry.name === "submit_result");
	assert.ok(tool?.prepareArguments);
	return normalizeSubmission(tool.prepareArguments(raw));
}

void test("a NOT_MET evidence correction resubmits without inventing an optional question", () => {
	for (const openQuestions of [[], null]) {
		// The live refusal carried both an unknown lookedFor and an empty optional list.
		const sent = {
			...result,
			evidence: {
				search,
				lookedFor: "Calls that grant access without checking authentication",
				sourceKind: "scm.pull-request.diff",
				openQuestions,
			},
		};
		let refusal: unknown;
		try {
			prepareSubmittedResult(sent);
		} catch (error) {
			refusal = error;
		}
		assert.ok(refusal instanceof Error);
		const start = "Corrected call example: ";
		const end = ". Example shows shape only;";
		assert.ok(refusal.message.includes(start));
		const correction: unknown = JSON.parse(
			refusal.message.slice(
				refusal.message.indexOf(start) + start.length,
				refusal.message.indexOf(end),
			),
		);
		assert.deepEqual(correction, { ...result, evidence: { search } });
		const resubmitted = prepareSubmittedResult(correction);
		assert.equal(resubmitted.outcome, "NOT_MET");
		assert.deepEqual(resubmitted.problems, result.problems);
		assert.deepEqual(resubmitted.evidence, { search });
	}
});

void test("only an absent optional NOT_MET question list is omitted; substantive questions remain", () => {
	for (const openQuestions of [[], null, "[]"]) {
		const sent = { ...result, evidence: { search, openQuestions } };
		assert.deepEqual(prepareSubmittedResult(sent).evidence, { search });
		assert.deepEqual(
			sent.evidence.openQuestions,
			openQuestions,
			"the original call is not changed",
		);
	}
	assert.deepEqual(prepareSubmittedResult(result).evidence, result.evidence);
	for (const openQuestions of ["", {}, "not a question list", false]) {
		assert.throws(() => prepareSubmittedResult({ ...result, evidence: { openQuestions } }));
	}
});

void test("required outcome warrants and NOT_MET problems are never optionalized", () => {
	for (const openQuestions of [undefined, null, []]) {
		assert.throws(() =>
			prepareSubmittedResult({
				...result,
				outcome: "COULD_NOT_DETERMINE",
				problems: [],
				example: null,
				evidence: { openQuestions },
			}),
		);
	}
	for (const problems of [undefined, null, []]) {
		assert.throws(() => prepareSubmittedResult({ ...result, problems, evidence: {} }));
	}
	assert.equal(
		prepareSubmittedResult({
			...result,
			outcome: "COULD_NOT_DETERMINE",
			problems: [],
			example: null,
		}).outcome,
		"COULD_NOT_DETERMINE",
	);
	assert.equal(
		prepareSubmittedResult({
			...result,
			outcome: "DOES_NOT_APPLY",
			problems: [],
			example: null,
			evidence: { inapplicability },
		}).outcome,
		"DOES_NOT_APPLY",
	);
	assert.equal(
		prepareSubmittedResult({ ...result, outcome: "MET", problems: [], evidence: { search } })
			.outcome,
		"MET",
	);
});

function refusalOf(submit: () => unknown): string {
	try {
		submit();
	} catch (error) {
		assert.ok(error instanceof Error);
		return error.message;
	}
	assert.fail("The call was accepted");
}

/** The JSON example that follows a correction label in a refusal. */
function exampleAfter(message: string, label: string): object {
	const start = message.indexOf(label);
	assert.notEqual(start, -1, message);
	const text = message.slice(start + label.length);
	for (let end = text.indexOf("}."); end >= 0; end = text.indexOf("}.", end + 1)) {
		let example: unknown;
		try {
			example = JSON.parse(text.slice(0, end + 1));
		} catch {
			// The example continues past this delimiter.
			example = undefined;
		}
		if (typeof example === "object" && example !== null && !Array.isArray(example)) {
			return example;
		}
	}
	assert.fail(message);
}

/** The facts of the refused review, sent in the declared shape. */
const submitted = {
	outcome: "NOT_MET",
	summary: result.summary,
	explanation: result.explanation,
	citations: [treeCitation],
	problems: [problem],
	evidence: {},
};

void test("a singular argument wrapper is refused, and its correction holds only declared fields for the original facts", () => {
	// The live refusals: the singular wrapper alone, then beside the declared fields, then alone again.
	const wrapped = { argument: JSON.stringify(submitted) };
	const refusal = refusalOf(() => prepareSubmittedResult(wrapped));
	assert.match(
		refusal,
		/^outcome: expected .* It leaves out argument: .* Replace outcome, summary, explanation, citations, evidence with the facts of your original call;.*no outcome was inferred\.$/u,
	);
	const template = exampleAfter(refusal, "Corrected call example: ");
	assert.deepEqual(template, {
		outcome: "<MET | NOT_MET | DOES_NOT_APPLY | COULD_NOT_DETERMINE>",
		summary: "<copy your summary>",
		explanation: "<copy your explanation>",
		citations: [],
		evidence: {},
	});
	assert.equal(Object.hasOwn(template, "argument"), false);
	// An outcome alone does not complete the template; the original facts still have to be supplied.
	assert.throws(() => prepareSubmittedResult({ ...template, outcome: "NOT_MET" }));
	const resubmitted = prepareSubmittedResult({ ...template, ...submitted });
	assert.deepEqual(resubmitted, prepareSubmittedResult(submitted));
	assert.equal(resubmitted.outcome, "NOT_MET");

	// The wrapper is still not read, whatever fields come beside it.
	const withOutcome = refusalOf(() => prepareSubmittedResult({ ...wrapped, outcome: "NOT_MET" }));
	assert.doesNotMatch(
		JSON.stringify(exampleAfter(withOutcome, "Corrected call example: ")),
		/"argument"/u,
	);
	assert.match(withOutcome, /It leaves out argument:/u);
});

void test("declared fields beside an argument key are refused, and the arguments-wrapped correction is accepted", () => {
	for (const sent of [
		{ ...submitted, argument: JSON.stringify(submitted) },
		{ arguments: { ...submitted, argument: JSON.stringify(submitted) } },
	]) {
		const refusal = refusalOf(() => prepareSubmittedResult(sent));
		assert.match(refusal, /^arguments: Unrecognized key: "argument"\. /u);
		const corrected = exampleAfter(refusal, "Corrected field example: ");
		assert.deepEqual(Object.keys(corrected), ["arguments"]);
		assert.deepEqual(prepareSubmittedResult(corrected), prepareSubmittedResult(submitted));
	}
	assert.deepEqual(
		prepareSubmittedResult({ arguments: submitted }),
		prepareSubmittedResult(submitted),
	);
});

void test("malformed inputs inside or beside a wrapper are still refused", () => {
	for (const sent of [
		{ argument: '{"outcome":"NOT_MET"' },
		{ arguments: { ...submitted, problems: '[{"summary":"Unchecked call"' } },
		{ arguments: { ...submitted, citations: [{ ...treeCitation, endLine: "end" }] } },
		{ arguments: { ...submitted, evidence: { search: "the diff" } } },
	]) {
		assert.throws(() => prepareSubmittedResult(sent), JSON.stringify(sent));
	}
	const envelope = refusalOf(() => prepareSubmittedResult({ arguments: '{"outcome":"MET"' }));
	assert.deepEqual(exampleAfter(envelope, "Corrected call example: "), {
		arguments: {
			outcome: "<MET | NOT_MET | DOES_NOT_APPLY | COULD_NOT_DETERMINE>",
			summary: "<copy your summary>",
			explanation: "<copy your explanation>",
			citations: [],
			evidence: {},
		},
	});
});
