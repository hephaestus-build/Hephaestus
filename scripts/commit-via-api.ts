/** GitHub signs API-created commits; expectedHeadOid rejects a concurrently moved branch. */
import { createHash } from "node:crypto";
import { appendFile, readFile } from "node:fs/promises";
import { isDeepStrictEqual, parseArgs } from "node:util";

import { isSet, requiredEnv } from "./lib/env.ts";
import { GitHubFailureError, isTransientGitHubFailure, retryTransient } from "./lib/github.ts";
import { asArray, asRecord, asString, at, isRecord } from "./lib/json.ts";
import { output, type RunOptions } from "./lib/process.ts";

export interface WorkTreeChanges {
	readonly additions: readonly string[];
	readonly deletions: readonly string[];
}

/** `CreateCommitOnBranchInput`, restricted to the fields a workflow commit uses. */
export interface CommitInput {
	readonly branch: { readonly repositoryNameWithOwner: string; readonly branchName: string };
	readonly expectedHeadOid: string;
	readonly message: { readonly headline: string; readonly body?: string };
	readonly fileChanges: {
		readonly additions: readonly { readonly path: string; readonly contents: string }[];
		readonly deletions: readonly { readonly path: string }[];
	};
}

export interface CommitTarget {
	readonly repository: string;
	readonly branch: string;
	readonly expectedHeadOid: string;
	readonly headline: string;
	readonly body?: string;
}

/** Stages the selected work-tree paths; automation callers use disposable checkouts. */
export async function stageChanges(
	paths: readonly string[] = [],
	options: RunOptions = {},
): Promise<WorkTreeChanges> {
	const conflicts = await output(
		"git",
		["diff", "--name-only", "--diff-filter=U", "-z", "--", ...paths],
		options,
	);
	if (conflicts) {
		throw new Error("Selected paths are unmerged; resolve conflicts before committing");
	}
	await output("git", ["add", "-A", "--", ...paths], options);
	const changed = async (filter: string): Promise<string[]> => {
		const names = await output(
			"git",
			[
				"diff",
				"--cached",
				"HEAD",
				"--no-renames",
				"--name-only",
				"-z",
				`--diff-filter=${filter}`,
				"--",
				...paths,
			],
			options,
		);
		return names.split("\0").filter(Boolean);
	};
	const [additions, deletions] = await Promise.all([changed("AMT"), changed("D")]);
	return { additions, deletions };
}

export function commitInput(
	target: CommitTarget,
	contents: ReadonlyMap<string, Uint8Array>,
	deletions: readonly string[],
): CommitInput {
	return {
		branch: { repositoryNameWithOwner: target.repository, branchName: target.branch },
		expectedHeadOid: target.expectedHeadOid,
		message: { headline: target.headline, body: target.body },
		fileChanges: {
			additions: [...contents].map(([path, bytes]) => ({
				path,
				contents: Buffer.from(bytes).toString("base64"),
			})),
			deletions: deletions.map((path) => ({ path })),
		},
	};
}

const COMMIT_MUTATION = `mutation ($input: CreateCommitOnBranchInput!) {
  createCommitOnBranch(input: $input) {
    commit {
      oid
      signature {
        state
        wasSignedByGitHub
      }
    }
  }
}`;

interface CreatedCommit {
	readonly oid: string;
	readonly description: string;
}

/** A REST read with the commit's token, so that a repeated commit needs no other credential. */
async function readGitHub(token: string, path: string): Promise<Record<string, unknown>> {
	const response = await fetch(
		`${process.env.GITHUB_API_URL ?? "https://api.github.com"}/${path}`,
		{
			headers: { authorization: `bearer ${token}`, accept: "application/vnd.github+json" },
		},
	);
	const text = await response.text();
	if (!response.ok) {
		throw new GitHubFailureError(
			`GitHub answered ${response.status} ${response.statusText} for ${path}`,
			`HTTP ${response.status} ${text}`,
		);
	}
	return asRecord(JSON.parse(text), path);
}

async function treeFiles(
	token: string,
	repository: string,
	revision: string,
): Promise<Map<string, string>> {
	const tree = await readGitHub(token, `repos/${repository}/git/trees/${revision}?recursive=1`);
	if (tree.truncated !== false) {
		throw new Error(`The tree of ${revision} is too large to compare`);
	}
	return new Map(
		asArray(tree.tree, "tree entries")
			.map((value) => asRecord(value, "tree entry"))
			.filter((entry) => entry.type !== "tree")
			.map((entry) => [asString(entry.path, "tree path"), asString(entry.sha, "tree sha")]),
	);
}

const blobSha = (contents: Buffer): string =>
	createHash("sha1").update(`blob ${contents.length}\0`).update(contents).digest("hex");

/**
 * Finds this commit on the branch after an attempt whose answer was lost. Undefined means that the
 * branch has not moved. A moved branch counts as this commit only when its head has the expected
 * parent, the headline, exactly the expected files and a verified signature, which a commit that
 * GitHub made has. Anything else is someone else's commit, so this throws.
 */
export async function landedCommit(
	token: string,
	input: CommitInput,
): Promise<CreatedCommit | undefined> {
	const { repositoryNameWithOwner: repository, branchName: branch } = input.branch;
	const ref = await readGitHub(token, `repos/${repository}/git/ref/heads/${branch}`);
	const head = asString(asRecord(ref.object, "ref object").sha, "ref sha");
	if (head === input.expectedHeadOid) {
		return undefined;
	}
	const commit = await readGitHub(token, `repos/${repository}/git/commits/${head}`);
	const parents = asArray(commit.parents, "parents").map((parent) =>
		asString(asRecord(parent, "parent").sha, "parent sha"),
	);
	const message = asString(commit.message, "commit message");
	const expected = await treeFiles(token, repository, input.expectedHeadOid);
	for (const { path } of input.fileChanges.deletions) {
		expected.delete(path);
	}
	for (const { path, contents } of input.fileChanges.additions) {
		expected.set(path, blobSha(Buffer.from(contents, "base64")));
	}
	if (
		!isDeepStrictEqual(parents, [input.expectedHeadOid]) ||
		message.split("\n")[0] !== input.message.headline ||
		asRecord(commit.verification, "verification").verified !== true ||
		!isDeepStrictEqual(await treeFiles(token, repository, head), expected)
	) {
		throw new Error(`${branch} moved to ${head}, which is not this commit`);
	}
	return { oid: head, description: `${head} (verified, found on the branch after a lost answer)` };
}

/**
 * Commits once, even when GitHub loses the answer to a commit that it made. expectedHeadOid already
 * prevents a duplicate: GitHub refuses a second attempt once the first has moved the branch. So each
 * new attempt first looks for the commit, and returns it instead of reporting that refusal.
 */
export async function createCommit(
	token: string,
	input: CommitInput,
	wait?: (milliseconds: number) => Promise<unknown>,
): Promise<CreatedCommit> {
	let attempted = false;
	return retryTransient(async () => {
		const repeating = attempted;
		attempted = true;
		const landed = repeating ? await landedCommit(token, input) : undefined;
		if (landed !== undefined) {
			return landed;
		}
		try {
			return await postCommit(token, input);
		} catch (error) {
			if (!repeating || isTransientGitHubFailure(error)) {
				throw error;
			}
			// The lost attempt can land after the look above, and then GitHub refuses this one.
			const late = await landedCommit(token, input);
			if (late === undefined) {
				throw error;
			}
			return late;
		}
	}, wait);
}

async function postCommit(token: string, input: CommitInput): Promise<CreatedCommit> {
	const response = await fetch(process.env.GITHUB_GRAPHQL_URL ?? "https://api.github.com/graphql", {
		method: "POST",
		headers: { authorization: `bearer ${token}`, "content-type": "application/json" },
		body: JSON.stringify({ query: COMMIT_MUTATION, variables: { input } }),
	});
	const text = await response.text();
	if (!response.ok) {
		throw new GitHubFailureError(
			`GitHub answered ${response.status} ${response.statusText}`,
			`HTTP ${response.status} ${text}`,
		);
	}
	const body = asRecord(JSON.parse(text), "GraphQL response");
	if (body.errors != null) {
		throw new GitHubFailureError(`createCommitOnBranch refused the commit: ${text}`, text);
	}
	const commit = asRecord(
		at(body, ["data", "createCommitOnBranch", "commit"], "response"),
		"commit",
	);
	const signature = isRecord(commit.signature) ? commit.signature : undefined;
	const verification = signature
		? `${asString(signature.state, "signature state")}, signed by GitHub: ${signature.wasSignedByGitHub === true}`
		: "unsigned";
	const oid = asString(commit.oid, "commit oid");
	return { oid, description: `${oid} (${verification})` };
}

async function currentHead(): Promise<string> {
	const oid = await output("git", ["rev-parse", "HEAD"]);
	return oid.trim();
}

if (import.meta.main) {
	const { values, positionals } = parseArgs({
		options: {
			branch: { type: "string" },
			message: { type: "string" },
			body: { type: "string" },
			"expected-head": { type: "string" },
		},
		allowPositionals: true,
	});
	const { branch, message } = values;
	if (!isSet(branch) || !isSet(message)) {
		throw new Error("--branch and --message are required");
	}
	const target: CommitTarget = {
		repository: requiredEnv(process.env, "GITHUB_REPOSITORY"),
		branch,
		headline: message,
		body: values.body,
		expectedHeadOid: values["expected-head"] ?? (await currentHead()),
	};
	const changes = await stageChanges(positionals);
	const changed = changes.additions.length > 0 || changes.deletions.length > 0;
	if (changed) {
		const contents = new Map(
			await Promise.all(
				changes.additions.map(async (path) => [path, await readFile(path)] as const),
			),
		);
		const token = requiredEnv(process.env, "GH_TOKEN");
		const commit = await createCommit(token, commitInput(target, contents, changes.deletions));
		console.log(`Committed ${commit.description} on ${branch}.`);
	} else {
		console.log("Selected paths match HEAD; no commit was created.");
	}
	const githubOutput = process.env.GITHUB_OUTPUT;
	if (isSet(githubOutput)) {
		await appendFile(githubOutput, `changed=${changed}\n`);
	}
}
