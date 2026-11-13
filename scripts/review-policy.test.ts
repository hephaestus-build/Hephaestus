import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";

import { parse as parseYaml } from "yaml";

import { asRecord } from "./lib/json.ts";

import {
	APPROVAL_MARKER,
	approvalBody,
	approvalStands,
	decide,
	enforce,
	type ActionsContext,
	type CreateReviewRequest,
	type GitHubApi,
	type PullRequest,
	type Review,
	parseMaintainers,
} from "./review-policy.ts";

const originalEnvironment = { ...process.env };

const HEAD = "0123456789abcdef0123456789abcdef01234567";
const OTHER = "fedcba9876543210fedcba9876543210fedcba98";

const ours = (over: Partial<Review> & Pick<Review, "id">): Review => ({
	node_id: `review-${over.id}`,
	state: "APPROVED",
	commit_id: HEAD,
	body: approvalBody("Maintainer"),
	user: { login: "github-actions[bot]" },
	...over,
});

const theirs = (over: Partial<Review> & Pick<Review, "id">): Review => ({
	node_id: `review-${over.id}`,
	state: "APPROVED",
	commit_id: HEAD,
	body: "Looks good.",
	user: { login: "reviewer" },
	...over,
});

const pull: PullRequest = {
	number: 7,
	head: { sha: HEAD },
	user: { login: "MaIntAiner" },
};

const makeCore = () => {
	const infos: string[] = [];
	const warnings: string[] = [];
	const failures: string[] = [];
	return {
		infos,
		warnings,
		failures,
		info: (message: string) => infos.push(message),
		warning: (message: string) => warnings.push(message),
		setFailed: (message: string) => failures.push(message),
	};
};

interface GitHubOptions {
	readonly resolvedPull?: PullRequest;
	readonly reviewDecision?: string | null;
	readonly reviewHead?: string;
	readonly reviewResponse?: unknown;
	readonly reviews?: readonly Review[];
	readonly failPullWith?: Error;
	readonly failReviewWith?: Error;
	readonly failMinimizeWith?: Error;
}

const makeGitHub = (options: GitHubOptions = {}) => {
	const submitted: CreateReviewRequest[] = [];
	const minimized: string[] = [];
	const reviews = [...(options.reviews ?? [])];
	const getPull = async () => {
		if (options.failPullWith) {
			throw options.failPullWith;
		}
		return { data: options.resolvedPull ?? pull };
	};

	const github: GitHubApi = {
		graphql: async (query, variables) => {
			if (query.startsWith("query")) {
				assert.deepEqual(variables, {
					...context.repo,
					number: (options.resolvedPull ?? pull).number,
				});
				if (options.reviewResponse !== undefined) return options.reviewResponse;
				return {
					repository: {
						pullRequest: {
							headRefOid: options.reviewHead ?? (options.resolvedPull ?? pull).head.sha,
							reviewDecision: options.reviewDecision === undefined ? null : options.reviewDecision,
						},
					},
				};
			}
			if (options.failMinimizeWith) {
				throw options.failMinimizeWith;
			}
			assert.ok(typeof variables.subjectId === "string");
			minimized.push(variables.subjectId);
			return {};
		},
		paginate: async () => reviews,
		rest: {
			pulls: {
				get: getPull,
				listReviews: async () => ({ data: reviews }),
				createReview: async (params) => {
					if (options.failReviewWith) {
						throw options.failReviewWith;
					}
					submitted.push(params);
					return { data: { id: submitted.length } };
				},
			},
		},
	};
	return { minimized, submitted, github };
};

const onlyReview = (submitted: readonly CreateReviewRequest[]): CreateReviewRequest => {
	assert.equal(submitted.length, 1);
	const [review] = submitted;
	assert.ok(review);
	return review;
};

const context: ActionsContext = {
	eventName: "workflow_run",
	repo: { owner: "owner", repo: "repo" },
	payload: { pull_request: { number: 7 } },
};

void describe("review policy", () => {
	beforeEach(() => {
		process.env.MAINTAINERS = "Maintainer";
	});

	afterEach(() => {
		process.env = { ...originalEnvironment };
	});

	void describe("parseMaintainers", () => {
		void it("splits, trims, lowercases and drops blanks", () => {
			assert.deepEqual([...parseMaintainers(" One, tWo ,, three,")], ["one", "two", "three"]);
		});

		void it("treats an unset variable as an empty allow-list", () => {
			assert.equal(parseMaintainers(undefined).size, 0);
			assert.equal(parseMaintainers("  ,  ").size, 0);
		});
	});

	void describe("approvalStands", () => {
		void it("recognises this module's own approval of the head commit", () => {
			assert.equal(approvalStands([ours({ id: 1 })], HEAD), true);
		});

		void it("ignores an approval recorded against an earlier commit", () => {
			assert.equal(approvalStands([ours({ id: 1, commit_id: OTHER })], HEAD), false);
		});

		void it("does not recognize copied markers as its own approval", () => {
			for (const user of [{ login: "reviewer" }, null]) {
				assert.equal(approvalStands([ours({ id: 1, user })], HEAD), false);
			}
			assert.equal(approvalStands([theirs({ id: 1 })], HEAD), false);
		});

		void it("treats its own dismissed approval as no longer standing", () => {
			const reviews = [ours({ id: 1 }), ours({ id: 2, state: "DISMISSED" })];
			assert.equal(approvalStands(reviews, HEAD), false);
		});

		void it("reads the latest decisive review whatever order they arrive in", () => {
			const reviews = [ours({ id: 5 }), ours({ id: 2, state: "DISMISSED" })];
			assert.equal(approvalStands(reviews, HEAD), true);
		});

		void it("lets a comment leave a standing approval alone", () => {
			const reviews = [ours({ id: 1 }), ours({ id: 2, state: "COMMENTED" })];
			assert.equal(approvalStands(reviews, HEAD), true);
		});

		void it("uses the bot's latest decisive position even without the policy marker", () => {
			for (const state of ["CHANGES_REQUESTED", "DISMISSED", "APPROVED"]) {
				assert.equal(
					approvalStands([ours({ id: 1 }), ours({ id: 2, state, body: "Another workflow" })], HEAD),
					false,
				);
			}
			assert.equal(
				approvalStands(
					[ours({ id: 1 }), ours({ id: 2, state: "COMMENTED", body: "Another workflow" })],
					HEAD,
				),
				true,
			);
		});

		void it("survives a review with no body at all", () => {
			for (const body of [null, undefined]) {
				assert.equal(approvalStands([ours({ id: 1, body })], HEAD), false);
			}
		});

		void it("finds no approval in an empty review list", () => {
			assert.equal(approvalStands([], HEAD), false);
		});
	});

	void describe("decide", () => {
		const base = {
			reviewDecision: null,
			author: "MaIntAiner",
			headSha: HEAD,
			reviews: [] as readonly Review[],
		};

		void it("approves a listed maintainer, matching the login case-insensitively", () => {
			const decision = decide({ ...base, maintainers: new Set(["maintainer"]) });
			assert.equal(decision.kind, "approve");
			assert.match(decision.reason, /REVIEW_POLICY_MAINTAINERS/u);
		});

		void it("does nothing for an author who is not listed", () => {
			const decision = decide({
				...base,
				author: "Contributor",
				maintainers: new Set(["maintainer"]),
			});
			assert.equal(decision.kind, "skip");
			assert.match(decision.reason, /needs an approval from someone with write access/u);
		});

		void it("approves nobody when the allow-list is empty", () => {
			assert.equal(decide({ ...base, maintainers: new Set() }).kind, "skip");
		});

		void it("does not approve a second time while its approval stands", () => {
			const decision = decide({
				...base,
				maintainers: new Set(["maintainer"]),
				reviews: [ours({ id: 1 })],
			});
			assert.equal(decision.kind, "standing");
		});

		void it("re-approves once a push has moved the head commit", () => {
			const decision = decide({
				...base,
				maintainers: new Set(["maintainer"]),
				reviews: [ours({ id: 1, commit_id: OTHER })],
			});
			assert.equal(decision.kind, "approve");
		});

		void it("ignores a stranger's approval when deciding whether to approve", () => {
			const decision = decide({
				...base,
				maintainers: new Set(["maintainer"]),
				reviews: [theirs({ id: 1 })],
			});
			assert.equal(decision.kind, "approve");
		});
	});

	void describe("enforce", () => {
		void it("submits an approving review for a listed maintainer, pinned to the head commit", async () => {
			const { submitted, github } = makeGitHub();
			const core = makeCore();
			await enforce({ github, context, core });

			const review = onlyReview(submitted);
			assert.equal(review.event, "APPROVE");
			assert.equal(review.pull_number, 7);
			assert.equal(review.commit_id, HEAD);
			assert.ok(review.body.includes(APPROVAL_MARKER));
			assert.deepEqual(core.failures, []);
		});

		void it("refreshes an exact-head approval when stacking makes GitHub require another review", async () => {
			const { submitted, minimized, github } = makeGitHub({
				reviews: [ours({ id: 5891029650 })],
				reviewDecision: "REVIEW_REQUIRED",
			});
			const core = makeCore();
			await enforce({ github, context, core });
			assert.equal(onlyReview(submitted).commit_id, HEAD);
			assert.deepEqual(minimized, ["review-5891029650"]);
			assert.deepEqual(core.failures, []);
		});

		void it("keeps its current approval when GitHub requires no fresh approval", async () => {
			for (const reviewDecision of ["APPROVED", "CHANGES_REQUESTED", null]) {
				const { submitted, github } = makeGitHub({ reviews: [ours({ id: 1 })], reviewDecision });
				await enforce({ github, context, core: makeCore() });
				assert.deepEqual(submitted, []);
			}
		});

		void it("fails closed on malformed native review metadata", async () => {
			for (const reviewResponse of [
				null,
				{},
				{ repository: { pullRequest: { headRefOid: 17, reviewDecision: "APPROVED" } } },
			]) {
				const { submitted, github } = makeGitHub({ reviewResponse });
				const core = makeCore();
				await enforce({ github, context, core });
				assert.deepEqual(submitted, []);
				assert.equal(core.failures.length, 1);
			}
		});

		void it("does not refresh approvals for an unlisted author or a changed head", async () => {
			for (const options of [
				{ resolvedPull: { ...pull, user: { login: "Contributor" } } },
				{ reviewHead: OTHER },
			]) {
				const { submitted, github } = makeGitHub({
					...options,
					reviews: [ours({ id: 1 })],
					reviewDecision: "REVIEW_REQUIRED",
				});
				await enforce({ github, context, core: makeCore() });
				assert.deepEqual(submitted, []);
			}
		});

		void it("fails closed on an unrecognized native review decision", async () => {
			const { submitted, github } = makeGitHub({
				reviews: [ours({ id: 1 })],
				reviewDecision: "UNKNOWN",
			});
			const core = makeCore();
			await enforce({ github, context, core });
			assert.deepEqual(submitted, []);
			assert.match(core.failures.join("\n"), /Unknown native review decision/u);
		});

		void it("minimizes earlier automatic approvals after submitting their replacement", async () => {
			const { minimized, github, submitted } = makeGitHub({
				reviews: [
					ours({ id: 1, commit_id: OTHER }),
					ours({ id: 2, commit_id: OTHER }),
					ours({ id: 3, user: { login: "reviewer" } }),
				],
			});
			await enforce({ github, context, core: makeCore() });

			assert.equal(submitted.length, 1);
			assert.deepEqual(minimized, ["review-1", "review-2"]);
		});

		void it("submits nothing for an author who is not on the allow-list", async () => {
			const { submitted, github } = makeGitHub({
				resolvedPull: { ...pull, user: { login: "Contributor" } },
			});
			const core = makeCore();
			await enforce({ github, context, core });

			assert.deepEqual(submitted, []);
			assert.deepEqual(core.failures, []);
		});

		void it("keeps the standing approval visible and minimizes its predecessors", async () => {
			const { minimized, submitted, github } = makeGitHub({
				reviews: [
					ours({ id: 1, commit_id: OTHER }),
					ours({ id: 2 }),
					ours({ id: 3, state: "COMMENTED" }),
				],
			});
			await enforce({ github, context, core: makeCore() });

			assert.deepEqual(submitted, []);
			assert.deepEqual(minimized, ["review-1"]);
		});

		void it("keeps a new approval when an old review cannot be minimized", async () => {
			const { submitted, github } = makeGitHub({
				reviews: [ours({ id: 1, commit_id: OTHER })],
				failMinimizeWith: new Error("minimization rejected"),
			});
			const core = makeCore();
			await enforce({ github, context, core });

			assert.equal(submitted.length, 1);
			assert.equal(core.warnings.length, 1);
			assert.match(String(core.warnings[0]), /minimization rejected/u);
			assert.deepEqual(core.failures, []);
		});

		void it("warns, and approves nobody, when the allow-list is unset", async () => {
			delete process.env.MAINTAINERS;
			const { submitted, github } = makeGitHub();
			const core = makeCore();
			await enforce({ github, context, core });

			assert.deepEqual(submitted, []);
			assert.equal(core.warnings.length, 1);
			assert.match(String(core.warnings[0]), /REVIEW_POLICY_MAINTAINERS/u);
			assert.deepEqual(core.failures, []);
		});

		void it("fails the job when the pull request cannot be read", async () => {
			const { submitted, github } = makeGitHub({ failPullWith: new Error("API is down") });
			const core = makeCore();
			await enforce({ github, context, core });

			assert.deepEqual(submitted, []);
			assert.equal(core.failures.length, 1);
			assert.match(String(core.failures[0]), /API is down/u);
		});

		void it("fails the job when the review cannot be submitted", async () => {
			const { github } = makeGitHub({ failReviewWith: new Error("review rejected") });
			const core = makeCore();
			await enforce({ github, context, core });

			assert.equal(core.failures.length, 1);
			assert.match(String(core.failures[0]), /review rejected/u);
		});

		void it("fails loudly when an event carries no pull request at all", async () => {
			const { submitted, github } = makeGitHub();
			const core = makeCore();
			await enforce({
				github,
				core,
				context: { eventName: "workflow_run", repo: context.repo, payload: {} },
			});

			assert.deepEqual(submitted, []);
			assert.equal(core.failures.length, 1);
		});
	});

	void describe("the workflow that applies it", () => {
		const collector = readFileSync(
			new URL("../.github/workflows/pull-request.yml", import.meta.url),
			"utf8",
		);
		const workflow = readFileSync(
			new URL("../.github/workflows/pull-request-policy.yml", import.meta.url),
			"utf8",
		);

		const configuration = asRecord(parseYaml(workflow), "pull request policy workflow");
		const jobs = asRecord(configuration.jobs, "jobs");
		const approvalJob = asRecord(jobs.approve, "approve");
		const approvalSource = JSON.stringify(approvalJob);

		void it("keeps the checkout on the default branch's copy of the policy", () => {
			assert.match(
				approvalSource,
				/"ref":"\$\{\{ github\.event\.repository\.default_branch \}\}"/u,
			);
			assert.doesNotMatch(workflow, /ref: \$\{\{ github\.sha \}\}/u);
			assert.doesNotMatch(workflow, /github\.event\.pull_request/u);
			for (const ref of workflow.matchAll(/^\s+ref: (?<ref>.+)$/gmu)) {
				assert.equal(ref.groups?.ref, `\${{ github.event.repository.default_branch }}`);
			}
		});

		void it("uses sparse checkout and keeps no credentials", () => {
			assert.match(approvalSource, /"persist-credentials":false/u);
			assert.match(approvalSource, /"sparse-checkout":"scripts"/u);
		});

		void it("asks for write access to pull requests and nothing else", () => {
			assert.deepEqual(configuration.permissions, {});
			assert.deepEqual(approvalJob.permissions, { contents: "read", "pull-requests": "write" });
		});

		void it("re-runs on every push, because the ruleset dismisses stale approvals", () => {
			assert.match(collector, /- synchronize\n/u);
			for (const action of ["opened", "reopened", "ready_for_review", "edited", "stacked"]) {
				assert.match(collector, new RegExp(`- ${action}\\n`, "u"));
			}
			assert.equal(approvalJob.needs, "resolve");
		});

		void it("runs for a stacked pull request, whatever branch it targets", () => {
			assert.doesNotMatch(collector, /^\s+branches:/mu);
			assert.doesNotMatch(String(approvalJob.if), /base/u);
		});

		void it("does not run for merge groups", () => {
			assert.doesNotMatch(workflow, /merge_group/u);
			assert.doesNotMatch(collector, /merge_group/u);
		});
	});
});
