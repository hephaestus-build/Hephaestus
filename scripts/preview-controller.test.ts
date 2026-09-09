import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import {
	assess,
	inventory,
	TEARDOWN_REQUESTED_DESCRIPTION,
	create,
	finalize,
	inactivate,
	PREVIEW_LABEL,
	progress,
	recheck,
	type GitHubApi,
	resolve,
	retire,
} from "./preview-controller.ts";

const originalEnvironment = { ...process.env };

const pull = {
	state: "open",
	draft: false,
	html_url: "https://github.example/owner/repo/pull/7",
	title: "Preview test",
	author_association: "COLLABORATOR",
	labels: [{ name: PREVIEW_LABEL }],
	base: { ref: "main" },
	head: { ref: "feature", sha: "head-sha", repo: { full_name: "owner/repo" } },
};

const unlabelled = { ...pull, labels: [{ name: "enhancement" }] };

const makeCore = () => {
	const outputs = new Map<string, string>();
	const failures: string[] = [];
	const notices: string[] = [];
	return {
		outputs,
		failures,
		notices,
		notice: (message: string) => notices.push(message),
		setFailed: (message: string) => failures.push(message),
		setOutput: (name: string, value: string) => outputs.set(name, value),
	};
};

const makeContext = () => ({
	serverUrl: "https://github.com",
	repo: { owner: "owner", repo: "repo" },
	payload: { repository: { default_branch: "main" }, pull_request: { number: 7 } },
});

type Deployment = { environment: string; id: number; sha: string };
type Status = { description?: string | null; state: string };

interface GitHubOptions {
	deployments?: Deployment[];
	files?: { filename: string }[];
	resolvedPull?: typeof pull;
	statuses?: Record<number, Status[]>;
	behindFiles?: { filename: string; sha?: string }[];
	branchBlobs?: Record<string, string>;
	defaultStatuses?: Status[];
}

const postedStatuses: Record<string, unknown>[] = [];

const makeGitHub = ({
	deployments = [{ environment: "preview/pr-7", id: 1, sha: "old-sha" }],
	files = [],
	resolvedPull = pull,
	statuses = {},
	behindFiles = [],
	branchBlobs = {},
	defaultStatuses = [],
}: GitHubOptions = {}): GitHubApi => ({
	paginate: async <T>(
		endpoint: (params: Record<string, unknown>) => Promise<{ data: T[] }>,
		params: Record<string, unknown>,
	) => (await endpoint(params)).data,
	rest: {
		pulls: {
			get: () => Promise.resolve({ data: resolvedPull }),
		},
		repos: {
			compareCommitsWithBasehead: (params) => {
				// Two directions, and they answer different questions. `main...head` is the whole
				// stack this pull request would deploy, not just its own layer's diff. `head...main`
				// is what the default branch has that this branch does not, which is what decides
				// whether a restored staging schema still fits it.
				if (params.basehead === `${resolvedPull.head.sha}...main`) {
					return Promise.resolve({ data: { files: behindFiles } });
				}
				assert.equal(params.basehead, `main...${resolvedPull.head.sha}`);
				return Promise.resolve({ data: { files } });
			},
			getContent: (params: Record<string, unknown>) => {
				const sha = branchBlobs[String(params.path)];
				if (sha === undefined) return Promise.reject(new Error("404"));
				return Promise.resolve({ data: { sha } });
			},
			createDeployment: () =>
				Promise.resolve({ data: { environment: "preview/pr-7", id: 2, sha: "head-sha" } }),
			createDeploymentStatus: (params: Record<string, unknown>) => {
				postedStatuses.push(params);
				return Promise.resolve({ data: {} });
			},
			deleteDeployment: () => Promise.resolve({ data: {} }),
			listDeployments: (params) =>
				Promise.resolve({
					data:
						typeof params.environment === "string"
							? deployments.filter((entry) => entry.environment === params.environment)
							: deployments,
				}),
			listDeploymentStatuses: (params) =>
				Promise.resolve({ data: statuses[Number(params.deployment_id)] ?? defaultStatuses }),
		},
	},
});

beforeEach(() => {
	Object.assign(process.env, {
		COOLIFY_URL: "https://coolify.example",
		COOLIFY_PREVIEW_URL_TEMPLATE: "https://pr{pr}.example",
	});
});

afterEach(() => {
	process.env = { ...originalEnvironment };
});

void describe("preview controller admission", () => {
	void it("deploys an opted-in head with current successful CI", async () => {
		const core = makeCore();
		await resolve({ github: makeGitHub(), context: makeContext(), core });

		assert.equal(core.outputs.get("eligible"), "true");
		assert.equal(core.outputs.get("head_sha"), "head-sha");
		assert.equal(core.outputs.get("environment"), "preview/pr-7");
		assert.equal(core.outputs.get("preview_url"), "https://pr7.example/");
	});

	void it("stays silent on a pull request that never opted in", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ resolvedPull: unlabelled }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.equal(core.outputs.get("announce"), "false");
	});

	void it("explains a fork instead of handing it deployment credentials", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				resolvedPull: { ...pull, head: { ...pull.head, repo: { full_name: "contributor/fork" } } },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.equal(core.outputs.get("announce"), "true");
		assert.match(core.outputs.get("reason") ?? "", /fork/);
	});

	void it("deploys a draft, which is usually the point of asking for a preview", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ resolvedPull: { ...pull, draft: true } }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("refuses PR-controlled changes to any part of the deployment control plane", async () => {
		for (const filename of [
			".github/workflows/reusable-docker-build.yml",
			".github/actions/setup-toolchain/action.yml",
			"docker/preview/compose.app.yaml",
			"docker/preview/.env.example",
		]) {
			const core = makeCore();
			await resolve({
				github: makeGitHub({ files: [{ filename }] }),
				context: makeContext(),
				core,
			});

			assert.equal(core.outputs.get("eligible"), "false", filename);
			assert.match(core.outputs.get("reason") ?? "", /trusted deployment policy/, filename);
		}
	});

	void it("refuses a comparison too large for GitHub to report in full", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				files: Array.from({ length: 300 }, (_unused, index) => ({ filename: `src/f${index}.ts` })),
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /deployment policy cannot be verified/);
	});

	void it("deploys a stacked layer, whose base is another pull request's branch", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ resolvedPull: { ...pull, base: { ref: "feat/layer-1" } } }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
		// Coolify picks its application by this ref, and that application tracks the default branch.
		assert.equal(core.outputs.get("base_ref"), "main");
	});

	void it("skips a pull request whose author is not a repository collaborator", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ resolvedPull: { ...pull, author_association: "CONTRIBUTOR" } }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /not a repository collaborator/);
	});

	void it("refuses a preview URL template that cannot name the pull request", async () => {
		process.env.COOLIFY_PREVIEW_URL_TEMPLATE = "https://preview.example";
		await assert.rejects(
			resolve({ github: makeGitHub(), context: makeContext(), core: makeCore() }),
			/must contain \{pr\}/,
		);
	});

	void it("keeps quiet when the current head is already deployed", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				deployments: [{ environment: "preview/pr-7", id: 2, sha: "head-sha" }],
				statuses: { 2: [{ state: "success" }] },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.equal(core.outputs.get("announce"), "false");
	});
});

void describe("preview host capacity", () => {
	const occupants = (count: number): Deployment[] =>
		Array.from({ length: count }, (_unused, index) => ({
			environment: `preview/pr-${100 + index}`,
			id: 100 + index,
			sha: `head-${index}`,
		}));

	void it("names the occupants when the host is full", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ deployments: occupants(3) }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.equal(core.outputs.get("announce"), "true");
		assert.match(core.outputs.get("reason") ?? "", /#100, #101, #102/);
	});

	void it("keeps updating a preview that already holds a slot on a full host", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				deployments: [...occupants(2), { environment: "preview/pr-7", id: 1, sha: "old-sha" }],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("does not count a verified cleanup tombstone against the host", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				deployments: occupants(3),
				statuses: {
					102: [{ description: TEARDOWN_REQUESTED_DESCRIPTION, state: "inactive" }],
				},
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("sweeps only the previews that still hold a slot", async () => {
		const core = makeCore();
		await inventory({
			github: makeGitHub({
				deployments: occupants(3),
				// pr-102's teardown was recorded, so re-sending its close event would ask Coolify to
				// remove a stack that is already gone — and would spend the sweep's bound doing it.
				statuses: {
					102: [{ description: TEARDOWN_REQUESTED_DESCRIPTION, state: "inactive" }],
				},
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("previews"), JSON.stringify([100, 101]));
	});

	void it("still counts a preview whose cleanup was never verified", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				deployments: occupants(3),
				statuses: { 102: [{ description: "Superseded", state: "inactive" }] },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
	});

	void it("honours a configured host limit", async () => {
		process.env.PREVIEW_MAX_ACTIVE = "1";
		const core = makeCore();
		await resolve({
			github: makeGitHub({ deployments: occupants(1) }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /1\/1/);
	});
});

void it("registers GitHub deployments against the immutable head SHA", async () => {
	Object.assign(process.env, {
		HEAD_SHA: "head-sha",
		ENVIRONMENT: "preview/pr-7",
		PR_NUMBER: "7",
		PREVIEW_URL: "https://pr7.example",
		SOURCE_RUN_URL: "https://github.example/runs/50",
	});
	let deploymentRef = "";
	let initialState = "";
	const baseGitHub = makeGitHub();
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: (params: Record<string, unknown>) => {
					initialState = String(params.state);
					return Promise.resolve({ data: {} });
				},
				createDeployment: (params: Record<string, unknown>) => {
					deploymentRef = String(params.ref);
					assert.equal(params.task, "deploy:preview");
					assert.equal(params.transient_environment, true);
					return Promise.resolve({
						data: { environment: "preview/pr-7", id: 2, sha: "head-sha" },
					});
				},
			},
		},
	};
	const core = makeCore();

	await create({ github, context: makeContext(), core });

	assert.equal(deploymentRef, "head-sha");
	assert.equal(initialState, "queued");
	assert.equal(core.outputs.get("deployment_id"), "2");
});

void it("stands down without failing when the head moved during preflight", async () => {
	Object.assign(process.env, { HEAD_SHA: "head-sha", PR_NUMBER: "7" });
	const core = makeCore();
	await recheck({
		github: makeGitHub({ resolvedPull: { ...pull, head: { ...pull.head, sha: "pushed-sha" } } }),
		context: makeContext(),
		core,
	});

	assert.equal(core.outputs.get("proceed"), "false");
	assert.deepEqual(core.failures, []);
});

void it("stands down without failing when the label was removed during preflight", async () => {
	Object.assign(process.env, { HEAD_SHA: "head-sha", PR_NUMBER: "7" });
	const core = makeCore();
	await recheck({ github: makeGitHub({ resolvedPull: unlabelled }), context: makeContext(), core });

	assert.equal(core.outputs.get("proceed"), "false");
	assert.deepEqual(core.failures, []);
});

void it("fails if the head stopped being a branch in this repository during preflight", async () => {
	Object.assign(process.env, { HEAD_SHA: "head-sha", PR_NUMBER: "7" });
	const core = makeCore();
	await recheck({
		github: makeGitHub({
			resolvedPull: { ...pull, head: { ...pull.head, repo: { full_name: "contributor/fork" } } },
		}),
		context: makeContext(),
		core,
	});

	assert.equal(core.outputs.get("proceed"), undefined);
	assert.equal(core.failures.length, 1);
});

void it("lets a stacked layer through preflight", async () => {
	Object.assign(process.env, { HEAD_SHA: "head-sha", PR_NUMBER: "7" });
	const core = makeCore();
	await recheck({
		github: makeGitHub({ resolvedPull: { ...pull, base: { ref: "feat/layer-1" } } }),
		context: makeContext(),
		core,
	});

	assert.equal(core.outputs.get("proceed"), "true");
});

void it("lets an unchanged, still-labelled head through", async () => {
	Object.assign(process.env, { HEAD_SHA: "head-sha", PR_NUMBER: "7" });
	const core = makeCore();
	await recheck({ github: makeGitHub(), context: makeContext(), core });

	assert.equal(core.outputs.get("proceed"), "true");
});

void it("marks a successful deployment and explicitly inactivates its predecessor", async () => {
	Object.assign(process.env, {
		DEPLOYMENT_ID: "2",
		DESCRIPTION: "Preview ready",
		ENVIRONMENT: "preview/pr-7",
		FINAL_STATE: "success",
		LOG_URL: "https://coolify.example/logs/2",
		PREVIEW_URL: "https://pr7.example",
		PR_NUMBER: "7",
		SOURCE_RUN_URL: "https://github.example/runs/50",
	});
	const statuses: Record<string, unknown>[] = [];
	const deleted: number[] = [];
	const baseGitHub = makeGitHub({
		deployments: [
			{ environment: "preview/pr-7", id: 2, sha: "head-sha" },
			{ environment: "preview/pr-7", id: 1, sha: "old" },
		],
	});
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: (parameters) => {
					statuses.push(parameters);
					return Promise.resolve({ data: {} });
				},
				deleteDeployment: (parameters) => {
					deleted.push(Number(parameters.deployment_id));
					return Promise.resolve({ data: {} });
				},
			},
		},
	};

	await finalize({ github, context: makeContext(), core: makeCore() });

	assert.deepEqual(
		statuses.map((status) => [status.deployment_id, status.state]),
		[
			[2, "success"],
			[1, "inactive"],
		],
	);
	assert.deepEqual(deleted, [1]);
});

void it("lets cleanup own the final state when the preview opted out mid-deployment", async () => {
	Object.assign(process.env, {
		DEPLOYMENT_ID: "2",
		DESCRIPTION: "Preview ready",
		ENVIRONMENT: "preview/pr-7",
		FINAL_STATE: "success",
		LOG_URL: "https://coolify.example/logs/2",
		PREVIEW_URL: "https://pr7.example",
		PR_NUMBER: "7",
		SOURCE_RUN_URL: "https://github.example/runs/50",
	});
	const statuses: Record<string, unknown>[] = [];
	const baseGitHub = makeGitHub({ resolvedPull: unlabelled });
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: (parameters) => {
					statuses.push(parameters);
					return Promise.resolve({ data: {} });
				},
			},
		},
	};

	await finalize({ github, context: makeContext(), core: makeCore() });

	assert.equal(statuses.length, 1);
	assert.equal(statuses[0]?.state, "inactive");
});

void it("retires deployment records only after marking them inactive", async () => {
	Object.assign(process.env, { ENVIRONMENT: "preview/pr-7" });
	const operations: string[] = [];
	const baseGitHub = makeGitHub({
		deployments: [{ environment: "preview/pr-7", id: 1, sha: "old" }],
		defaultStatuses: [{ state: "success" }],
	});
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: () => {
					operations.push("inactive");
					return Promise.resolve({ data: {} });
				},
				deleteDeployment: () => {
					operations.push("delete");
					return Promise.resolve({ data: {} });
				},
			},
		},
	};

	await retire({ github, context: makeContext(), core: makeCore() });

	assert.deepEqual(operations, ["inactive", "delete"]);
});

void it("keeps the verified tombstone record when cleanup inactivates a preview", async () => {
	Object.assign(process.env, { ENVIRONMENT: "preview/pr-7" });
	const descriptions: string[] = [];
	let deletions = 0;
	const baseGitHub = makeGitHub({
		deployments: [{ environment: "preview/pr-7", id: 1, sha: "old" }],
		defaultStatuses: [{ state: "inactive" }],
	});
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: (parameters) => {
					descriptions.push(String(parameters.description));
					return Promise.resolve({ data: {} });
				},
				deleteDeployment: () => {
					deletions += 1;
					return Promise.resolve({ data: {} });
				},
			},
		},
	};

	await inactivate({ github, context: makeContext(), core: makeCore() });

	assert.deepEqual(descriptions, [TEARDOWN_REQUESTED_DESCRIPTION]);
	assert.equal(deletions, 0);
});

void describe("reconcile staleness", () => {
	beforeEach(() => {
		process.env.PR_NUMBER = "7";
	});

	void it("leaves an open, labelled, ready pull request alone", async () => {
		const core = makeCore();
		await assess({ github: makeGitHub(), context: makeContext(), core });

		assert.equal(core.outputs.get("stale"), "false");
		assert.equal(core.outputs.get("url"), undefined);
	});

	void it("reclaims a preview whose pull request dropped the label", async () => {
		const core = makeCore();
		await assess({
			github: makeGitHub({ resolvedPull: unlabelled }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("stale"), "true");
		assert.equal(core.outputs.get("head_sha"), "head-sha");
		assert.equal(core.outputs.get("base_ref"), "main");
	});

	void it("reclaims a closed pull request", async () => {
		const core = makeCore();
		await assess({
			github: makeGitHub({ resolvedPull: { ...pull, state: "closed" } }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("stale"), "true");
	});

	void it("leaves a draft alone, since the label is what opts in", async () => {
		const core = makeCore();
		await assess({
			github: makeGitHub({ resolvedPull: { ...pull, draft: true } }),
			context: makeContext(),
			core,
		});

		// Reclaiming here would tear the preview down moments after the deploy that created it.
		assert.equal(core.outputs.get("stale"), "false");
	});
});

void describe("preview deployment lifecycle", () => {
	beforeEach(() => {
		postedStatuses.length = 0;
	});

	void it("moves the deployment while the run waits, so it is not silent for most of its life", async () => {
		for (const [state, description] of [
			["queued", "Waiting for CI to publish signed images for this commit."],
			["in_progress", "Images are ready; Coolify is building the preview."],
		] as const) {
			process.env.DEPLOYMENT_ID = "42";
			process.env.STATE = state;
			process.env.DESCRIPTION = description;
			process.env.ENVIRONMENT = "preview/pr-7";
			process.env.PREVIEW_URL = "https://pr7.example/";
			process.env.SOURCE_RUN_URL = "https://runs.example/1";

			await progress({ github: makeGitHub({}), context: makeContext(), core: makeCore() });
		}

		assert.deepEqual(
			postedStatuses.map((status) => status.state),
			["queued", "in_progress"],
		);
		// The environment link is what makes GitHub render a destination rather than a bare record.
		const [first] = postedStatuses;
		assert.ok(first);
		assert.equal(first.environment_url, "https://pr7.example/");
		assert.equal(first.log_url, "https://runs.example/1");
	});

	void it("reports only states GitHub treats as still running", async () => {
		process.env.DEPLOYMENT_ID = "42";
		process.env.STATE = "success";
		process.env.DESCRIPTION = "done";
		process.env.ENVIRONMENT = "preview/pr-7";
		process.env.PREVIEW_URL = "https://pr7.example/";
		process.env.SOURCE_RUN_URL = "https://runs.example/1";

		await assert.rejects(
			() => progress({ github: makeGitHub({}), context: makeContext(), core: makeCore() }),
			/queued or in_progress/,
		);
		assert.equal(postedStatuses.length, 0);
	});
});

void describe("preview teardown reporting", () => {
	void it("says a closed pull request is closed, which is what authorizes the delete", async () => {
		process.env.PR_NUMBER = "2042";
		const core = makeCore();
		await assess({
			github: makeGitHub({ resolvedPull: { ...pull, state: "closed" } }),
			context: makeContext(),
			core,
		});
		assert.equal(core.outputs.get("stale"), "true");
		assert.equal(core.outputs.get("closed"), "true");
	});

	void it("keeps the environment of an open pull request that merely dropped the label", async () => {
		process.env.PR_NUMBER = "2042";
		const core = makeCore();
		await assess({
			github: makeGitHub({ resolvedPull: { ...pull, labels: [] } }),
			context: makeContext(),
			core,
		});
		assert.equal(core.outputs.get("stale"), "true");
		assert.equal(core.outputs.get("closed"), "false");
	});
});

void describe("preview schema drift", () => {
	const migration = {
		filename: "server/application/src/main/resources/db/changelog/0001_drop.xml",
		sha: "blob-1",
	};

	void it("refuses a branch missing one of the default branch's migrations", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ behindFiles: [migration] }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		const reason = core.outputs.get("reason") ?? "";
		// The three things the author needs: which file, what goes wrong, and what to do about it.
		assert.match(reason, /0001_drop\.xml/);
		assert.match(reason, /have failed against it/);
		assert.match(reason, /Merge main in/);
		// Both mechanisms were reproduced against a restored database, so the reason names them —
		// and keeps them apart, because only one of the two stops the boot.
		assert.match(reason, /Liquibase stops the boot/);
		assert.match(reason, /starts and then fails the first query/);
		// It still reports what has gone wrong, never what this branch is guaranteed to hit: a
		// migration that only adds a table this branch never queries breaks nothing. The two
		// phrasings that overclaimed — Hibernate validation, which `prod` disables, and a schema
		// mismatch a differing blob does not prove — may not come back.
		assert.doesNotMatch(reason, /would fail|never produced|no longer match|cannot boot/);
		assert.equal(core.outputs.get("announce"), "true");
	});

	void it("deploys a branch that already carries the migration under another commit", async () => {
		// A cherry-pick satisfies the schema while its ancestry still reports the file, so the blob
		// decides. Refusing here would ground a branch whose schema is fine.
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [migration],
				branchBlobs: { [migration.filename]: "blob-1" },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("refuses when the branch carries an older revision of that file", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [migration],
				branchBlobs: { [migration.filename]: "an-older-blob" },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
	});

	void it("refuses a branch too far behind to compare, naming the consequence not the tool", async () => {
		// The response caps at COMPARE_FILE_LIMIT and flags no truncation, so a saturated answer is
		// not evidence that no migration is missing.
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: Array.from({ length: 300 }, (_, index) => ({
					filename: `webapp/src/file-${index}.tsx`,
				})),
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		const reason = core.outputs.get("reason") ?? "";
		// Every branch that has reached this was genuinely incompatible, so the reason says what will
		// happen and how to fix it rather than reporting the comparison's own limit.
		assert.match(reason, /behind main/);
		// Truncation is the one case where the check genuinely could not run, so the reason says so,
		// and says which check: whether the branch still carries the default branch's migrations.
		assert.match(reason, /carries main's migrations/);
		assert.match(reason, /cannot be checked/);
		assert.match(reason, /have failed against it/);
		assert.match(reason, /Merge main in/);
		assert.doesNotMatch(reason, /would fail|never produced|no longer match|cannot boot/);
	});

	void it("ignores prose under the schema directory", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [{ filename: "server/application/src/main/resources/db/README.md" }],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("leaves a live preview alone rather than replacing it with a refusal", async () => {
		// Head H deploys, a migration then lands on the default branch, and something re-resolves H.
		// No deployment is needed, so the working preview's comment must survive.
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [migration],
				deployments: [{ environment: "preview/pr-7", id: 2, sha: pull.head.sha }],
				statuses: { 2: [{ state: "success" }] },
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		// Quiet: the reason is the live deployment, and it posts no comment.
		assert.equal(core.outputs.get("announce"), "false");
		assert.match(core.outputs.get("reason") ?? "", /already has a current preview/);
	});

	void it("deploys a branch that is merely behind on code, which is almost every branch", async () => {
		// Matching Java too would refuse a preview after any merge at all.
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [
					{ filename: "server/application/src/main/java/de/tum/cit/aet/hephaestus/Foo.java" },
					{ filename: "webapp/src/routes/index.tsx" },
					{ filename: "docs/contributor/ci-cd.mdx" },
				],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});
});
