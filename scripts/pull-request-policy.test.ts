import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import { parse as parseYaml } from "yaml";

import { asRecord, asString } from "./lib/json.ts";
import {
	parseRecord,
	resolvePullRequest,
	titleProblem,
	type TitlePolicy,
} from "./pull-request-policy.ts";

// The workflow imports the same export from the default branch's file, without an install.
const configuration: unknown = await import(
	new URL("../commitlint.config.ts", import.meta.url).href
);
const exported = asRecord(
	asRecord(configuration, "commitlint.config.ts").pullRequestTitlePolicy,
	"title policy",
);
const titlePolicy = {
	types: asString(exported.types, "types"),
	scopes: asString(exported.scopes, "scopes"),
	headerMaxLength: Number(exported.headerMaxLength),
	headerPattern: asString(exported.headerPattern, "headerPattern"),
	subjectPattern: asString(exported.subjectPattern, "subjectPattern"),
	breakingMarkerPattern: asString(exported.breakingMarkerPattern, "breakingMarkerPattern"),
} satisfies TitlePolicy;

const problem = (title: string) => titleProblem(title, titlePolicy);

const repo = { owner: "hephaestus-build", repo: "Hephaestus" };
const head = "a".repeat(40);

function api(overrides: { readonly state?: string; readonly headSha?: string } = {}) {
	const requested: unknown[] = [];
	return {
		requested,
		github: {
			rest: {
				pulls: {
					get: async (params: Record<string, unknown>) => {
						requested.push(params.pull_number);
						return {
							data: {
								number: Number(params.pull_number),
								state: overrides.state ?? "open",
								draft: false,
								title: "fix(webapp): a title",
								head: { sha: overrides.headSha ?? head },
								base: { ref: "main" },
								user: { login: "octocat", type: "User" },
							},
						};
					},
				},
			},
		},
	};
}

void describe("pull request policy", () => {
	void it("reads only a number and a known action from the record", () => {
		assert.deepEqual(parseRecord("4142\nopened\n"), { number: 4142, action: "opened" });
		assert.deepEqual(parseRecord("7\nsynchronize"), { number: 7, action: "synchronize" });
		for (const forged of [
			"",
			"4142\n",
			"0\nopened\n",
			"-1\nopened\n",
			"4142\nclosed\n",
			"4142 \nopened\n",
			"4142\nopened\nextra\n",
			"1234567890\nopened\n",
		]) {
			assert.equal(parseRecord(forged), undefined, JSON.stringify(forged));
		}
	});

	void it("acts on the recorded pull request only while its head is the tested commit", async () => {
		const current = api();
		assert.deepEqual(
			await resolvePullRequest({ ...current, repo, record: "12\nopened\n", headSha: head }),
			{
				number: 12,
				draft: false,
				base: "main",
				author: "octocat",
				authorIsUser: true,
				opened: true,
			},
		);
		const replaced = api({ headSha: "b".repeat(40) });
		assert.equal(
			await resolvePullRequest({ ...replaced, repo, record: "12\nsynchronize\n", headSha: head }),
			undefined,
		);
		const closed = api({ state: "closed" });
		assert.equal(
			await resolvePullRequest({ ...closed, repo, record: "12\nedited\n", headSha: head }),
			undefined,
		);
	});

	void it("reads nothing from the API for a malformed record or head", async () => {
		const unread = api();
		assert.equal(
			await resolvePullRequest({ ...unread, repo, record: "12; rm -rf /\n", headSha: head }),
			undefined,
		);
		assert.equal(
			await resolvePullRequest({ ...unread, repo, record: "12\nopened\n", headSha: "main" }),
			undefined,
		);
		assert.deepEqual(unread.requested, []);
	});

	void it("holds the title to the policy that the commit-msg hook enforces", () => {
		assert.equal(problem("fix(webapp): the list no longer jumps while it loads"), "");
		assert.equal(problem("docs: the runbook names its source"), "");
		assert.match(problem("feature(webapp): a list"), /type `feature`/u);
		assert.match(problem("fix(frontend): a list"), /scope `frontend`/u);
		assert.match(problem("fix(webapp): a list."), /end with a period/u);
		assert.match(problem("fix(webapp)!: a list"), /no ! marker/u);
		assert.match(problem("a list that jumps"), /form `type\(scope\): description`/u);
		assert.match(problem("fix: one\ntwo"), /single line/u);
		assert.match(problem(`fix: ${"x".repeat(100)}`), /the limit is 100/u);
	});

	void describe("its workflows", () => {
		const collector = readFileSync(
			new URL("../.github/workflows/pull-request.yml", import.meta.url),
			"utf8",
		);
		const trusted = readFileSync(
			new URL("../.github/workflows/pull-request-policy.yml", import.meta.url),
			"utf8",
		);
		const collectorJobs = asRecord(asRecord(parseYaml(collector), "collector").jobs, "jobs");
		const trustedJobs = asRecord(asRecord(parseYaml(trusted), "trusted").jobs, "jobs");

		void it("records the pull request with no permissions and no checkout", () => {
			assert.match(collector, /^ {2}pull_request:$/mu);
			assert.match(collector, /^permissions: \{\}$/mu);
			assert.deepEqual(Object.keys(collectorJobs), ["record"]);
			assert.equal(asRecord(collectorJobs.record, "record").permissions, undefined);
			assert.doesNotMatch(collector, /actions\/checkout|secrets\.|vars\./u);
		});

		void it("runs the policy from the default branch, on the completed run, and reads the record as data", () => {
			assert.match(trusted, /^ {2}workflow_run:/mu);
			assert.match(trusted, /workflows: \["Repo \/ Pull request"\]/u);
			assert.doesNotMatch(trusted, /pull_request_target/u);
			// Into the runner's temporary directory: no later step loads a file from the record.
			assert.match(trusted, /run-id: \$\{\{ github\.event\.workflow_run\.id \}\}/u);
			assert.match(trusted, /path: \$\{\{ runner\.temp \}\}\/recorded/u);
			const resolve = asRecord(trustedJobs.resolve, "resolve");
			assert.match(String(resolve.if), /workflow_run\.conclusion == 'success'/u);
			for (const name of ["validate-pr", "approve", "assign-author"]) {
				assert.equal(asRecord(trustedJobs[name], name).needs, "resolve", name);
			}
		});

		void it("reports the verdict on the pull request's head as the `Validate PR` check", () => {
			const validate = asRecord(trustedJobs["validate-pr"], "validate-pr");
			assert.deepEqual(validate.permissions, {
				checks: "write",
				contents: "read",
				"pull-requests": "write",
			});
			assert.match(trusted, /name: "Validate PR",\n\s+head_sha: process\.env\.HEAD_SHA,/u);
			// The job's own result shows on main's head commit, so only the posted check fails.
			assert.doesNotMatch(trusted, /core\.setFailed\(`The pull request policy failed/u);
		});
	});
});
