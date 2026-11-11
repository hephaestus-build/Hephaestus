import { spawnSync } from "node:child_process";

import { asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export const DOCS_PREVIEW_CONTEXT = "Preview / Docs";

export type PreviewState = "unclaimed" | "pending" | "success" | "error" | "failure";
type PreviewEvent = "claim" | "deployed" | "unavailable";

/** A head gets one claim. A terminal result never starts another deployment. */
export function previewTransition(state: PreviewState, event: PreviewEvent): PreviewState {
	if (state === "unclaimed" && event === "claim") {
		return "pending";
	}
	if (state === "pending" && event === "deployed") {
		return "success";
	}
	if (state === "pending" && event === "unavailable") {
		return "error";
	}
	throw new Error(`Illegal docs preview transition: ${state} -> ${event}`);
}

function sha(value: unknown): string {
	const result = asString(value, "head commit");
	if (!/^[a-f0-9]{40}$/u.test(result)) {
		throw new Error("Expected a full commit SHA.");
	}
	return result;
}

/** PR path filters use the whole PR diff; a later code-only push must not deploy it again. */
export function docsChanged(event: unknown, cwd = process.cwd()): boolean {
	const payload = asRecord(event, "pull request event");
	const pull = asRecord(payload.pull_request, "pull request");
	const head = sha(asRecord(pull.head, "pull request head").sha);
	const action = asString(payload.action, "pull request action");
	if (!["opened", "reopened", "synchronize"].includes(action)) {
		throw new Error("The pull request action cannot select a docs preview.");
	}
	const synchronized = action === "synchronize";
	const base = sha(synchronized ? payload.before : asRecord(pull.base, "pull request base").sha);
	// A force push can leave the previous head outside the fetched branches.
	if (
		spawnSync("git", ["cat-file", "-e", `${base}^{commit}`], { cwd, stdio: "ignore" }).status !== 0
	) {
		const fetched = spawnSync("git", ["fetch", "--quiet", "--no-tags", "origin", base], {
			cwd,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		});
		if (fetched.status !== 0) {
			throw new Error(`Cannot read the comparison commit: ${fetched.stderr}`);
		}
	}
	const result = spawnSync(
		"git",
		[
			"diff",
			"--quiet",
			synchronized ? base : `${base}...${head}`,
			...(synchronized ? [head] : []),
			"--",
			"docs/",
		],
		{ cwd, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status === 0) {
		return false;
	}
	if (result.status === 1) {
		return true;
	}
	throw new Error(`Cannot compare documentation: ${result.stderr}`);
}

type ApiMethod<T> = (params: Record<string, unknown>) => Promise<{ data: T }>;
interface GitHubApi {
	paginate: <T>(method: ApiMethod<T[]>, params: Record<string, unknown>) => Promise<T[]>;
	rest: {
		pulls: { get: ApiMethod<unknown> };
		repos: {
			listCommitStatusesForRef: ApiMethod<unknown[]>;
			createCommitStatus: ApiMethod<unknown>;
		};
	};
}

interface PreviewInput {
	github: GitHubApi;
	repo: { owner: string; repo: string };
	pullNumber: number;
	headSha: string;
	runUrl: string;
}

async function currentPull({
	github,
	repo,
	pullNumber,
}: Pick<PreviewInput, "github" | "repo" | "pullNumber">) {
	const response = await github.rest.pulls.get({ ...repo, pull_number: pullNumber });
	const pull = asRecord(response.data, "current pull request");
	const state = asString(pull.state, "pull request state");
	if (state !== "open" && state !== "closed") {
		throw new Error("The pull request state is invalid.");
	}
	return { state, head: pull.head };
}

/** A closed event can wait behind an upload until after the pull request reopens. */
export async function shouldTeardownPreviews(
	input: Pick<PreviewInput, "github" | "repo" | "pullNumber">,
): Promise<boolean> {
	const pull = await currentPull(input);
	return pull.state === "closed";
}

function recordedState(value: unknown): Exclude<PreviewState, "unclaimed"> {
	switch (value) {
		case "pending":
		case "success":
		case "error":
		case "failure": {
			return value;
		}
		default: {
			throw new Error("The recorded docs preview status is invalid.");
		}
	}
}

/** Call inside the workflow's per-PR serialization, immediately before the Surge step. */
export async function claimDocsPreview({
	github,
	repo,
	pullNumber,
	headSha,
	runUrl,
}: PreviewInput) {
	sha(headSha);
	const pull = await currentPull({ github, repo, pullNumber });
	if (pull.state !== "open" || sha(asRecord(pull.head, "current head").sha) !== headSha) {
		return {
			kind: "superseded",
			summary: "This pull request is closed or its head changed. No preview was deployed.",
		};
	}
	const statuses = await github.paginate(github.rest.repos.listCommitStatusesForRef, {
		...repo,
		ref: headSha,
		per_page: 100,
	});
	const previous = statuses
		.map((value) => asRecord(value, "commit status"))
		.find((status) => status.context === DOCS_PREVIEW_CONTEXT);
	if (previous) {
		const state = recordedState(previous.state);
		return {
			kind: "existing",
			summary: `This head already has a ${state} docs preview status. No deployment was repeated.`,
		};
	}
	await github.rest.repos.createCommitStatus({
		...repo,
		sha: headSha,
		context: DOCS_PREVIEW_CONTEXT,
		state: previewTransition("unclaimed", "claim"),
		target_url: runUrl,
		description: "Docs preview deployment claimed for this head",
	});
	return { kind: "claimed", summary: "This head has claimed its one docs preview deployment." };
}

/** Only the run that created the claim calls this, with its actual Surge step outcome. */
export async function finishDocsPreview({
	github,
	repo,
	headSha,
	runUrl,
	previewUrl,
	outcome,
}: Omit<PreviewInput, "pullNumber"> & { previewUrl: string; outcome: string }) {
	sha(headSha);
	if (!["success", "failure", "cancelled", "skipped"].includes(outcome)) {
		throw new Error("The Surge step outcome is invalid.");
	}
	const state = previewTransition("pending", outcome === "success" ? "deployed" : "unavailable");
	await github.rest.repos.createCommitStatus({
		...repo,
		sha: headSha,
		context: DOCS_PREVIEW_CONTEXT,
		state,
		target_url: state === "success" ? previewUrl : runUrl,
		description:
			state === "success"
				? "Docs preview is ready"
				: "Docs preview unavailable; read the workflow log",
	});
	return {
		state,
		summary:
			state === "success"
				? `Docs preview deployed: ${previewUrl}`
				: `Docs preview was not published (Surge step: ${outcome}). The preview status links to this run's log. This head will not deploy again.`,
	};
}
