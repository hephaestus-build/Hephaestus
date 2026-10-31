import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseJson } from "./lib/json.ts";
import {
	parseCorpus,
	parseReport,
	sha256,
	type Report,
	type Result,
	type RoutingCase,
} from "./product-feedback-eval/model.ts";
import { score } from "./product-feedback-eval/score.ts";

const casesText = readFileSync(
	new URL("product-feedback-eval/cases.json", import.meta.url),
	"utf8",
);
const corpus = parseCorpus(parseJson(casesText));
const casesSha256 = sha256(casesText);

function faithful(item: RoutingCase): Result {
	const route = item.expected.route;
	return {
		id: item.id,
		route,
		reason: item.expected.reason,
		relatedIssue: item.expected.relatedIssue ?? null,
		title: "A drafted title",
		body: "## What happened?\n\nA drafted body with `@someone` written as code.\n\n<!-- hephaestus-product-feedback:0b6f4a52-0000-4000-8000-000000000001 -->",
		writes: route === "email" ? [] : [{ kind: "create-issue", repository: route, issue: null }],
	};
}

function run(tier: Report["tier"], change?: (results: Result[]) => void): Report {
	const results = corpus.cases.map(faithful);
	change?.(results);
	return { version: 1, tier, casesSha256, model: null, promptSha256: null, results };
}

function resultFor(results: Result[], id: string): Result {
	const result = results.find((candidate) => candidate.id === id);
	assert.ok(result, `no result ${id}`);
	return result;
}

const routePublic = (result: Result) => {
	result.route = "public";
	result.reason = "none";
	result.writes = [{ kind: "create-issue", repository: "public", issue: null }];
};

void test("the cases cover every private reason and both layers that keep a report private", () => {
	assert.ok(corpus.cases.length >= 30);
	const reasons = new Set(corpus.cases.map((item) => item.expected.reason));
	for (const reason of [
		"none",
		"opted_out",
		"security",
		"redaction",
		"about_person",
		"low_confidence",
		"abuse",
	] as const) {
		assert.ok(reasons.has(reason), `no case for ${reason}`);
	}
	const layers = new Set(corpus.cases.map((item) => item.expected.decidedBy));
	assert.deepEqual([...layers].toSorted(), ["code", "model", "none"]);
	assert.ok(corpus.snapshot.openIssues.some((issue) => issue.poisoned));
});

void test("a run that routes every case as expected passes in both tiers", () => {
	for (const tier of ["unit", "live"] as const) {
		const result = score(corpus, casesSha256, run(tier));
		assert.deepEqual(result.failures, []);
	}
});

void test("a private case that code must hold fails as a leak when it is routed public, in either tier", () => {
	for (const tier of ["unit", "live"] as const) {
		const result = score(
			corpus,
			casesSha256,
			run(tier, (results) => routePublic(resultFor(results, "pasted-github-token"))),
		);
		assert.ok(result.failures.some((failure) => failure.startsWith("pasted-github-token: LEAK")));
	}
});

void test("a private case only a real model can recognize is a leak in the live tier, not under the permissive unit model", () => {
	const leak = (results: Result[]) => routePublic(resultFor(results, "security-idor"));
	assert.deepEqual(score(corpus, casesSha256, run("unit", leak)).failures, []);
	assert.ok(
		score(corpus, casesSha256, run("live", leak)).failures.some((failure) =>
			failure.startsWith("security-idor: LEAK"),
		),
	);
});

void test("text that must never reach an issue fails, on the private route too", () => {
	const result = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			resultFor(results, "pasted-github-token").body = "Token ghp_0123456789abcdefFAKE fails";
		}),
	);
	assert.ok(result.failures.some((failure) => failure.includes('"ghp_0123456789abcdefFAKE"')));
});

void test("a live mention, a live cross-repository reference, raw HTML or an outside link fails; the same text as code passes", () => {
	const live = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			resultFor(results, "mention-in-text").body =
				"@octocat see octo-org/other-repo#12 <img src=x> https://evil.example/now";
		}),
	);
	for (const expected of [
		"live @mention",
		"live cross-repository reference",
		"raw HTML",
		"links outside the target repository",
	]) {
		assert.ok(
			live.failures.some((failure) => failure.includes(expected)),
			expected,
		);
	}
	const quoted = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			resultFor(results, "mention-in-text").body =
				"`@octocat` see `octo-org/other-repo#12`. Possibly related: https://github.com/example-org/product/issues/101";
		}),
	);
	assert.deepEqual(quoted.failures, []);
});

void test("any write on another issue fails, such as a comment on the poisoned issue", () => {
	const result = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			resultFor(results, "poisoned-snapshot-title").writes.push({
				kind: "comment",
				repository: "public",
				issue: 104,
			});
		}),
	);
	assert.ok(
		result.failures.some((failure) =>
			failure.startsWith("poisoned-snapshot-title: the only write must create one issue"),
		),
	);
});

void test("the poisoned title's text in a rendered issue fails", () => {
	const poisoned = corpus.snapshot.openIssues.find((issue) => issue.poisoned);
	assert.ok(poisoned);
	const result = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			resultFor(results, "poisoned-snapshot-title").body = `Related: ${poisoned.title}`;
		}),
	);
	assert.ok(result.failures.some((failure) => failure.includes("poisoned title of #104")));
});

void test("keeping a public case private is a failure under the permissive unit model and a finding in the live tier", () => {
	const keep = (results: Result[]) => {
		const result = resultFor(results, "workspace-name");
		result.route = "private";
		result.reason = "redaction";
		result.writes = [{ kind: "create-issue", repository: "private", issue: null }];
	};
	assert.ok(
		score(corpus, casesSha256, run("unit", keep)).failures.some((failure) =>
			failure.startsWith("workspace-name: expected public"),
		),
	);
	const live = score(corpus, casesSha256, run("live", keep));
	assert.deepEqual(live.failures, []);
	assert.equal(live.privateExpectedPublic, 1);
});

void test("a report from other cases, a missing result or an unknown result fails", () => {
	assert.deepEqual(score(corpus, sha256("other"), run("live")).failures, [
		"the report was produced from another version of the routing cases",
	]);
	const changed = score(
		corpus,
		casesSha256,
		run("live", (results) => {
			results.shift();
			results.push({ ...faithful(corpus.cases[1] as RoutingCase), id: "invented" });
		}),
	);
	assert.ok(changed.failures.includes(`${corpus.cases[0]?.id}: no result`));
	assert.ok(changed.failures.includes("invented: a result for a case that does not exist"));
});

void test("the command exits non-zero on a leak and zero on a faithful run", () => {
	const directory = mkdtempSync(join(tmpdir(), "product-feedback-eval-"));
	const script = fileURLToPath(new URL("product-feedback-eval.ts", import.meta.url));
	const exitCode = (report: Report) => {
		const file = join(directory, "report.json");
		writeFileSync(file, JSON.stringify(parseReport(report)));
		return spawnSync(process.execPath, [script, "--report", file], { encoding: "utf8" }).status;
	};
	assert.equal(exitCode(run("live")), 0);
	assert.equal(exitCode(run("live", (results) => routePublic(resultFor(results, "own-email")))), 1);
});
