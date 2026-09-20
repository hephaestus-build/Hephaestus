import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { isMap, isSeq, parseDocument } from "yaml";

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

interface Deployment {
	environment: string;
	id: number;
	sha: string;
}
interface Status {
	description?: string | null;
	state: string;
}

interface GitHubOptions {
	deployments?: Deployment[];
	files?: { filename: string; sha?: string }[];
	/** The tree both branches left. Only a fixture about a deletion or a rename needs it. */
	baseFiles?: { filename: string; sha?: string }[];
	resolvedPull?: typeof pull;
	statuses?: Record<number, Status[]>;
	behindFiles?: { filename: string; sha?: string }[];
	branchBlobs?: Record<string, string>;
	defaultStatuses?: Status[];
}

const MERGE_BASE_SHA = "merge-base-sha";
const DEFAULT_BRANCH_SHA = "main-sha";

const postedStatuses: Record<string, unknown>[] = [];

const makeGitHub = ({
	deployments = [{ environment: "preview/pr-7", id: 1, sha: "old-sha" }],
	files = [],
	baseFiles = [],
	resolvedPull = pull,
	statuses = {},
	behindFiles = [],
	branchBlobs = {},
	defaultStatuses = [],
}: GitHubOptions = {}): GitHubApi => ({
	paginate: async <T>(
		endpoint: (params: Record<string, unknown>) => Promise<{ data: T[] }>,
		params: Record<string, unknown>,
	) => {
		const page = await endpoint(params);
		return page.data;
	},
	rest: {
		pulls: {
			get: async () => ({ data: resolvedPull }),
		},
		git: {
			// The branches diverge from `baseFiles`, empty unless a test is about something leaving a
			// tree, so `files` is what this head added and `behindFiles` what the default branch did.
			getTree: async (params: Record<string, unknown>) => {
				const trees: Record<string, { filename: string; sha?: string }[]> = {
					[MERGE_BASE_SHA]: baseFiles,
					[resolvedPull.head.sha]: files,
					[DEFAULT_BRANCH_SHA]: behindFiles,
				};
				const entries = trees[String(params.tree_sha)];
				assert.ok(entries, `unexpected tree ${String(params.tree_sha)}`);
				assert.equal(params.recursive, "1");
				return {
					data: {
						truncated: false,
						tree: entries.map((entry) => ({
							path: entry.filename,
							type: "blob",
							sha: entry.sha ?? `blob-of-${entry.filename}`,
						})),
					},
				};
			},
		},
		repos: {
			compareCommitsWithBasehead: async (params) => {
				// The direction matters: `main...head` makes the merge base the point this branch left
				// the default branch, which is what both checks are diffed from.
				assert.equal(params.basehead, `main...${resolvedPull.head.sha}`);
				return {
					data: {
						base_commit: { sha: DEFAULT_BRANCH_SHA },
						merge_base_commit: { sha: MERGE_BASE_SHA },
					},
				};
			},
			getContent: async (params: Record<string, unknown>) => {
				const sha = branchBlobs[String(params.path)];
				if (sha === undefined) {
					throw new Error("404");
				}
				return { data: { sha } };
			},
			createDeployment: async () => ({
				data: { environment: "preview/pr-7", id: 2, sha: "head-sha" },
			}),
			createDeploymentStatus: async (params: Record<string, unknown>) => {
				postedStatuses.push(params);
				return { data: {} };
			},
			deleteDeployment: async () => ({ data: {} }),
			listDeployments: async (params) => ({
				data:
					typeof params.environment === "string"
						? deployments.filter((entry) => entry.environment === params.environment)
						: deployments,
			}),
			listDeploymentStatuses: async (params) => ({
				data: statuses[Number(params.deployment_id)] ?? defaultStatuses,
			}),
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
		assert.match(core.outputs.get("reason") ?? "", /fork/u);
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
			assert.match(core.outputs.get("reason") ?? "", /trusted deployment policy/u, filename);
		}
	});

	void it("admits a branch with more files than one comparison reports", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				files: Array.from({ length: 1200 }, (_unused, index) => ({ filename: `src/f${index}.ts` })),
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "true");
	});

	void it("finds deployment policy changed past where a comparison stops reporting", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				files: [
					...Array.from({ length: 1200 }, (_unused, index) => ({ filename: `src/f${index}.ts` })),
					{ filename: "docker/preview/compose.app.yaml" },
				],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /trusted deployment policy/u);
	});

	void it("refuses a head that took a file out of the deployment control plane", async () => {
		// A comparison counts a rename as one file and names only where it landed.
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				baseFiles: [{ filename: "docker/preview/compose.app.yaml" }],
				files: [{ filename: "docker/compose.app.yaml" }],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /docker\/preview\/compose\.app\.yaml/u);
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
		assert.match(core.outputs.get("reason") ?? "", /not a repository collaborator/u);
	});

	void it("refuses a preview URL template that cannot name the pull request", async () => {
		process.env.COOLIFY_PREVIEW_URL_TEMPLATE = "https://preview.example";
		await assert.rejects(
			resolve({ github: makeGitHub(), context: makeContext(), core: makeCore() }),
			/must contain \{pr\}/u,
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

function occupants(count: number): Deployment[] {
	return Array.from({ length: count }, (_unused, index) => ({
		environment: `preview/pr-${100 + index}`,
		id: 100 + index,
		sha: `head-${index}`,
	}));
}

void describe("preview host capacity", () => {
	void it("names the occupants when the host is full", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({ deployments: occupants(3) }),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.equal(core.outputs.get("announce"), "true");
		assert.match(core.outputs.get("reason") ?? "", /#100, #101, #102/u);
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
		assert.match(core.outputs.get("reason") ?? "", /1\/1/u);
	});
});

void it("registers GitHub deployments against the immutable head SHA", async () => {
	Object.assign(process.env, {
		HEAD_SHA: "head-sha",
		ENVIRONMENT: "preview/pr-7",
		PR_NUMBER: "7",
		PREVIEW_URL: "https://pr7.example",
		PR_TITLE: "feat(webapp): a readable title",
		PR_URL: "https://github.example/pull/7",
		SOURCE_RUN_URL: "https://github.example/runs/50",
	});
	let deploymentRef = "";
	let initialState = "";
	let description = "";
	let payload: unknown;
	const baseGitHub = makeGitHub();
	const github: GitHubApi = {
		...baseGitHub,
		rest: {
			...baseGitHub.rest,
			repos: {
				...baseGitHub.rest.repos,
				createDeploymentStatus: async (params: Record<string, unknown>) => {
					initialState = String(params.state);
					return { data: {} };
				},
				createDeployment: async (params: Record<string, unknown>) => {
					deploymentRef = String(params.ref);
					description = String(params.description);
					({ payload } = params);
					assert.equal(params.task, "deploy:preview");
					assert.equal(params.transient_environment, true);
					return {
						data: { environment: "preview/pr-7", id: 2, sha: "head-sha" },
					};
				},
			},
		},
	};
	const core = makeCore();

	await create({ github, context: makeContext(), core });

	assert.equal(deploymentRef, "head-sha");
	assert.equal(initialState, "queued");
	assert.equal(core.outputs.get("deployment_id"), "2");
	// GitHub renders the environment name on the pull request and the description where every
	// environment is listed together, so the description is what tells one preview from another.
	assert.equal(description, "PR #7 · feat(webapp): a readable title");
	assert.ok(typeof payload === "object" && payload !== null);
	assert.equal(Reflect.get(payload, "pull_request_url"), "https://github.example/pull/7");
	assert.equal(Reflect.get(payload, "title"), "feat(webapp): a readable title");
});

void it("refuses to open a deployment that would carry no title or pull request link", async () => {
	// A deployment that describes itself as a bare number, or carries an empty link in the payload
	// that rides on every status event, is worse than one that never opened: it reports success and
	// identifies nothing.
	for (const missing of ["PR_TITLE", "PR_URL"]) {
		Object.assign(process.env, {
			HEAD_SHA: "head-sha",
			ENVIRONMENT: "preview/pr-7",
			PR_NUMBER: "7",
			PREVIEW_URL: "https://pr7.example",
			PR_TITLE: "feat(webapp): a readable title",
			PR_URL: "https://github.example/pull/7",
			SOURCE_RUN_URL: "https://github.example/runs/50",
		});
		Reflect.deleteProperty(process.env, missing);
		await assert.rejects(
			async () => create({ github: makeGitHub(), context: makeContext(), core: makeCore() }),
			new RegExp(missing, "u"),
		);
	}
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
				createDeploymentStatus: async (parameters) => {
					statuses.push(parameters);
					return { data: {} };
				},
				deleteDeployment: async (parameters) => {
					deleted.push(Number(parameters.deployment_id));
					return { data: {} };
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
				createDeploymentStatus: async (parameters) => {
					statuses.push(parameters);
					return { data: {} };
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
				createDeploymentStatus: async () => {
					operations.push("inactive");
					return { data: {} };
				},
				deleteDeployment: async () => {
					operations.push("delete");
					return { data: {} };
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
				createDeploymentStatus: async (parameters) => {
					descriptions.push(String(parameters.description));
					return { data: {} };
				},
				deleteDeployment: async () => {
					deletions += 1;
					return { data: {} };
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
			async () => progress({ github: makeGitHub({}), context: makeContext(), core: makeCore() }),
			/queued or in_progress/u,
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
		assert.match(reason, /0001_drop\.xml/u);
		assert.match(reason, /have failed to start against it/u);
		assert.match(reason, /Merge main in/u);
		// Both mechanisms were reproduced against a restored database, so the reason names them, and
		// names the step each one stops at: neither branch reaches a started application.
		assert.match(reason, /Liquibase re-runs migrations/u);
		assert.match(reason, /startup then fails on a column/u);
		// It reports what has gone wrong, never what this branch is guaranteed to hit: a migration
		// that only adds a table this branch never queries breaks nothing. Two causes it may not
		// claim are Hibernate validation, which `prod` disables, and a schema mismatch that a
		// differing blob does not prove.
		assert.doesNotMatch(reason, /would fail|never produced|no longer match|cannot boot|Hibernate/u);
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

	void it("checks the schema of a branch further behind than a comparison reports", async () => {
		const core = makeCore();
		await resolve({
			github: makeGitHub({
				behindFiles: [
					...Array.from({ length: 1200 }, (_unused, index) => ({
						filename: `webapp/src/file-${index}.tsx`,
					})),
					migration,
				],
			}),
			context: makeContext(),
			core,
		});

		assert.equal(core.outputs.get("eligible"), "false");
		assert.match(core.outputs.get("reason") ?? "", /0001_drop\.xml/u);
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
		assert.match(core.outputs.get("reason") ?? "", /already has a current preview/u);
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

void describe("preview workflow reporting follows the finalized deployment", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/deploy-preview.yml", "utf8"));
	function runs(name: string, outputs: Record<string, Record<string, string>>) {
		const steps = workflow.getIn(["jobs", "deploy", "steps"]);
		assert.ok(isSeq(steps));
		const step = steps.items.find((item) => isMap(item) && item.get("name") === name);
		assert.ok(isMap(step));
		const condition = step.get("if");
		assert.ok(typeof condition === "string");
		const functions = new Map([
			[
				"always",
				{ name: "always", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(true) },
			],
		]);
		const expression = new Parser(
			new Lexer(condition).lex().tokens,
			["steps"],
			[...functions.values()],
		).parse();
		const context: unknown = JSON.parse(
			JSON.stringify({
				steps: Object.fromEntries(
					Object.entries(outputs).map(([id, values]) => [id, { outputs: values }]),
				),
			}),
			data.reviver,
		);
		assert.ok(context instanceof data.Dictionary);
		return new Evaluator(expression, context, functions).evaluate().coerceString() === "true";
	}

	for (const scenario of [
		{ name: "ready preview", state: "success", pull, comment: false, fail: false, link: true },
		{ name: "failed deployment", state: "failure", pull, comment: true, fail: true, link: false },
		{ name: "deployment error", state: "error", pull, comment: true, fail: true, link: false },
		{
			name: "superseded preflight",
			state: "inactive",
			pull,
			comment: false,
			fail: false,
			link: false,
		},
		{
			name: "label removed before a failure",
			state: "failure",
			pull: unlabelled,
			comment: false,
			fail: false,
			link: false,
		},
		{
			name: "pull request closed before an error",
			state: "error",
			pull: { ...pull, state: "closed" },
			comment: false,
			fail: false,
			link: false,
		},
	]) {
		void it(scenario.name, async () => {
			Object.assign(process.env, {
				DEPLOYMENT_ID: "2",
				DESCRIPTION: "Deployment finished",
				ENVIRONMENT: "preview/pr-7",
				FINAL_STATE: scenario.state,
				LOG_URL: "https://coolify.example/logs/2",
				PREVIEW_URL: "https://pr7.example",
				PR_NUMBER: "7",
				SOURCE_RUN_URL: "https://github.example/runs/50",
			});
			const core = makeCore();
			await finalize({
				github: makeGitHub({ resolvedPull: scenario.pull }),
				context: makeContext(),
				core,
			});
			const outputs = {
				context: { eligible: "true" },
				recheck: { proceed: scenario.state === "inactive" ? "false" : "true" },
				queue: { deployment_uuid: scenario.state === "inactive" ? "" : "deployment" },
				wait: { state: scenario.state === "inactive" ? "" : scenario.state },
				finalize: Object.fromEntries(core.outputs),
			};
			assert.equal(runs("Report a failed preview", outputs), scenario.comment);
			assert.equal(runs("Fail when the preview failed", outputs), scenario.fail);
			assert.equal(runs("Publish the preview link", outputs), scenario.link);
		});
	}

	void it("still reports a real failure before finalization or queueing completes", () => {
		const outputs = {
			context: { eligible: "true" },
			recheck: {},
			queue: {},
			wait: {},
			finalize: {},
		};
		assert.equal(runs("Report a failed preview", outputs), true);
		assert.equal(runs("Publish the preview link", outputs), false);
		assert.equal(runs("Fail when the preview failed", outputs), false);
		assert.equal(
			runs("Fail when the preview failed", {
				...outputs,
				queue: { deployment_uuid: "deployment" },
			}),
			true,
		);
		assert.equal(
			runs("Report a failed preview", { ...outputs, context: { eligible: "false" } }),
			false,
		);
	});
});
