import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import { parse as parseYaml } from "yaml";

import { asRecord, asString } from "./lib/json.ts";
import { RETIRED_NAME } from "./lib/retired-name.ts";
import {
	OUTSIDE_PLAN,
	outsidePlanReason,
	parseRecord,
	planLinkProblem,
	planReferences,
	resolvePullRequest,
	titleProblem,
	type PlannedPullRequest,
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
	retiredNamePattern: asString(exported.retiredNamePattern, "retiredNamePattern"),
	retiredNameMessage: asString(exported.retiredNameMessage, "retiredNameMessage"),
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

/** Issue bodies by number; a number that is absent answers 404, as the API does. */
function issues(bodies: Readonly<Record<number, { readonly body: string; readonly pull?: true }>>) {
	const requested: number[] = [];
	return {
		requested,
		github: {
			rest: {
				issues: {
					get: async (params: Record<string, unknown>) => {
						const number = Number(params.issue_number);
						requested.push(number);
						const issue = bodies[number];
						if (issue === undefined) {
							throw Object.assign(new Error("Not Found"), { status: 404 });
						}
						return {
							data: {
								body: issue.body,
								...(issue.pull === true ? { pull_request: { url: "" } } : {}),
							},
						};
					},
				},
			},
		},
	};
}

const board = { body: "<!-- heph-key: KR-04 -->\n\n### Outcome\n\nOpen codes." };
const unplanned = { body: "### Outcome\n\nA follow-up." };

function pull(
	body: string | null,
	headRef = "feat/a-change",
	headRepo: string | null = "hephaestus-build/Hephaestus",
) {
	return {
		body,
		head: { ref: headRef, repo: headRepo === null ? null : { full_name: headRepo } },
		base: { ref: "main" },
	} satisfies PlannedPullRequest;
}

const planned = { 4900: board, 12: unplanned, 13: unplanned };

const planProblem = async (
	body: string | null,
	bodies: Parameters<typeof issues>[0] = planned,
	headRef?: string,
	headRepo?: string | null,
) => planLinkProblem({ ...issues(bodies), repo, pull: pull(body, headRef, headRepo) });

void describe("pull request policy", () => {
	void it("reads only a number and a known action from the record", () => {
		assert.deepEqual(parseRecord("4142\nopened\n"), { number: 4142, action: "opened" });
		assert.deepEqual(parseRecord("7\nsynchronize"), { number: 7, action: "synchronize" });
		assert.deepEqual(parseRecord("4251\nstacked\n"), { number: 4251, action: "stacked" });
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
		const stacked = await resolvePullRequest({
			...current,
			repo,
			record: "12\nstacked\n",
			headSha: head,
		});
		assert.ok(stacked);
		assert.equal(stacked.number, 12);
		assert.equal(stacked.opened, false);
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

	void it("refuses the retired short name as a word and names the words to use", () => {
		assert.match(
			problem(`feat(conversation): ${RETIRED_NAME} reads the thread`),
			/Ask Hephaestus/u,
		);
		assert.match(problem(`fix: the answer from ${RETIRED_NAME}'s turn`), /retired/u);
		assert.equal(problem("feat(conversation): Hephaestus reads the thread"), "");
		assert.equal(problem("fix(scripts): the heph-tools sandbox stops"), "");
	});

	void describe("the 1.0 plan link", () => {
		void it("reads every closing keyword and `Part of`, in any case", () => {
			for (const keyword of [
				"close",
				"closes",
				"closed",
				"fix",
				"fixes",
				"fixed",
				"resolve",
				"resolves",
				"resolved",
				"part of",
				"CLOSES",
				"Part Of",
			]) {
				assert.deepEqual(planReferences(`${keyword} #4900`), [4900], keyword);
				assert.deepEqual(planReferences(`The fix.\n\n${keyword}: #4900.`), [4900], keyword);
			}
			assert.deepEqual(planReferences("Closes #12, part of #4900 and fixes #12"), [12, 4900]);
		});

		void it("reads no reference without a keyword, or to another repository or a longer word", () => {
			for (const body of [
				"See #4900.",
				"Closes hephaestus-build/other#4900",
				"Closes #4900abc",
				"prefixes #4900",
				"enclosed #4900",
				"Closes #0",
			]) {
				assert.deepEqual(planReferences(body), [], body);
			}
		});

		void it("ignores references and exemptions in comments, code and quotes", () => {
			const quoted = [
				'<!-- Link related work with "Fixes #4900". -->',
				"```md\nCloses #4900\nOutside the 1.0 plan: an example\n```",
				"~~~~\n```\nPart of #4900\n~~~~",
				"Write `Closes #4900` to close it.",
				"> Closes #4900\n> Outside the 1.0 plan: their words",
				"<!-- an unclosed comment\nCloses #4900",
			];
			for (const body of quoted) {
				assert.deepEqual(planReferences(body), [], body);
				assert.equal(outsidePlanReason(body), undefined, body);
			}
			assert.deepEqual(planReferences("```\ncode\n```\nCloses #4900"), [4900]);
		});

		void it("passes a reference to an issue on the 1.0 board", async () => {
			assert.equal(await planProblem("Fixes #4900"), "");
			assert.equal(await planProblem("Part of #4900"), "");
		});

		void it("passes a mix when one referenced issue is on the board, and reads no further", async () => {
			const fake = issues({ 4900: board, 12: unplanned });
			const verdict = await planLinkProblem({
				...fake,
				repo,
				pull: pull("Closes #12\nPart of #4900\nCloses #13"),
			});
			assert.equal(verdict, "");
			assert.deepEqual(fake.requested, [12, 4900]);
		});

		void it("refuses references to issues that are not on the board, and names them", async () => {
			assert.equal(
				await planProblem("Closes #12\nPart of #13\nFixes #99999"),
				"No issue that the description references (#12, #13, #99999) is on the 1.0 board.",
			);
		});

		void it("does not take a pull request or a marker in another shape for a board issue", async () => {
			const marked = { body: "<!-- heph-key: KR-04 -->", pull: true } as const;
			assert.match(await planProblem("Closes #7", { 7: marked }), /#7\) is on the 1\.0 board/u);
			const prose = { body: "The heph-key: KR-04 is in the text." };
			assert.match(await planProblem("Closes #8", { 8: prose }), /#8\) is on the 1\.0 board/u);
		});

		void it("passes the exemption only with a reason", async () => {
			assert.equal(OUTSIDE_PLAN, "Outside the 1.0 plan:");
			assert.equal(await planProblem("Outside the 1.0 plan: a CI runner fix."), "");
			assert.equal(await planProblem("## Why\n\n- outside the 1.0 plan: repository tooling"), "");
			assert.equal(
				outsidePlanReason("Outside the 1.0 plan:   \nOutside the 1.0 plan: tooling"),
				"tooling",
			);
			for (const body of [
				"Outside the 1.0 plan:",
				"Outside the 1.0 plan:  -- ",
				"Outside the 1.0 plan: …",
			]) {
				assert.equal(outsidePlanReason(body), "", body);
				assert.equal(
					await planProblem(body),
					"The `Outside the 1.0 plan:` line gives no reason.",
					body,
				);
			}
			assert.equal(outsidePlanReason("This is outside the 1.0 plan: tooling"), undefined);
		});

		void it("explains every gap when a reference and the exemption both fall short", async () => {
			assert.equal(
				await planProblem("Closes #12\n\nOutside the 1.0 plan:"),
				"No issue that the description references (#12) is on the 1.0 board. The `Outside the 1.0 plan:` line gives no reason.",
			);
		});

		void it("refuses a description that names nothing", async () => {
			for (const body of [null, "", 'A change.\n\n<!-- Link related work with "Fixes #123". -->']) {
				assert.match(await planProblem(body), /names no 1\.0 issue/u, String(body));
			}
		});

		void it("exempts Renovate and the Version PR from this repository, and reads no issue", async () => {
			const fake = issues({});
			for (const branch of [
				"renovate/vite",
				"renovate/lock-file-maintenance",
				"changeset-release/main",
			]) {
				assert.equal(
					await planLinkProblem({ ...fake, repo, pull: pull(null, branch) }),
					"",
					branch,
				);
			}
			assert.deepEqual(fake.requested, []);
		});

		void it("holds an automation branch name from a fork or a lookalike to the rule", async () => {
			assert.match(
				await planProblem(null, {}, "renovate/vite", "someone/Hephaestus"),
				/names no 1\.0 issue/u,
			);
			assert.match(
				await planProblem(null, {}, "changeset-release/main", null),
				/names no 1\.0 issue/u,
			);
			for (const branch of [
				"changeset-release/main-extra",
				"renovate-vite",
				"feat/renovate/vite",
			]) {
				assert.match(await planProblem(null, {}, branch), /names no 1\.0 issue/u, branch);
			}
		});

		void it("reads at most twenty referenced issues", async () => {
			const fake = issues({});
			const body = Array.from({ length: 30 }, (_, index) => `Part of #${index + 1}`).join("\n");
			await planLinkProblem({ ...fake, repo, pull: pull(body) });
			assert.equal(fake.requested.length, 20);
		});

		void it("fails, rather than refuses, when the API fails for another reason", async () => {
			const broken = {
				rest: {
					issues: {
						get: async () => {
							throw Object.assign(new Error("Bad credentials"), { status: 401 });
						},
					},
				},
			};
			await assert.rejects(
				planLinkProblem({ github: broken, repo, pull: pull("Closes #4900") }),
				/Bad credentials/u,
			);
		});
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

		void it("records the pull request with read access, checking out only the upload action from the default branch", () => {
			assert.match(collector, /^ {2}pull_request:$/mu);
			// On either event a checkout of the pull request would run its code with the base repository's token.
			assert.doesNotMatch(collector, /pull_request_target|workflow_run/u);
			assert.match(collector, /^permissions: \{\}$/mu);
			assert.deepEqual(Object.keys(collectorJobs), ["record"]);
			const record = asRecord(collectorJobs.record, "record");
			assert.deepEqual(record.permissions, { contents: "read" });
			assert.doesNotMatch(collector, /secrets\.|vars\./u);
			const checkouts = (Array.isArray(record.steps) ? record.steps : [])
				.map((step) => asRecord(step, "step"))
				.filter((step) => String(step.uses).startsWith("actions/checkout@"));
			assert.equal(checkouts.length, 1);
			assert.deepEqual(asRecord(checkouts[0]?.with, "checkout"), {
				// The default branch, as pull-request-policy.yml runs: a stored base or a lower stacked branch can predate the action.
				ref: `\${{ github.event.repository.default_branch }}`,
				"sparse-checkout": ".github/actions/upload-artifact-retry",
				"persist-credentials": false,
			});
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
				issues: "read",
				"pull-requests": "write",
			});
			assert.match(trusted, /name: "Validate PR",\n\s+head_sha: process\.env\.HEAD_SHA,/u);
			// The job's own result shows on main's head commit, so only the posted check fails.
			assert.doesNotMatch(trusted, /core\.setFailed\(`The pull request policy failed/u);
		});
	});
});
