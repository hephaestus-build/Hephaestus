/** GitHub signs API-created commits; expectedHeadOid rejects a concurrently moved branch. */
import { appendFile, readFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { isSet, requiredEnv } from "./lib/env.ts";
import { asRecord, asString, at, isRecord } from "./lib/json.ts";
import { output, type RunOptions } from "./lib/process.ts";

export interface WorkTreeChanges {
	readonly additions: readonly string[];
	readonly deletions: readonly string[];
}

/** `CreateCommitOnBranchInput`, restricted to the fields a workflow commit uses. */
export interface CommitInput {
	readonly branch: { readonly repositoryNameWithOwner: string; readonly branchName: string };
	readonly expectedHeadOid: string;
	readonly message: { readonly headline: string };
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
		message: { headline: target.headline },
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

async function createCommit(token: string, input: CommitInput): Promise<string> {
	const response = await fetch(process.env.GITHUB_GRAPHQL_URL ?? "https://api.github.com/graphql", {
		method: "POST",
		headers: { authorization: `bearer ${token}`, "content-type": "application/json" },
		body: JSON.stringify({ query: COMMIT_MUTATION, variables: { input } }),
	});
	const text = await response.text();
	if (!response.ok) {
		throw new Error(`GitHub answered ${response.status} ${response.statusText}`);
	}
	const body = asRecord(JSON.parse(text), "GraphQL response");
	if (body.errors != null) {
		throw new Error(`createCommitOnBranch refused the commit: ${text}`);
	}
	const commit = asRecord(
		at(body, ["data", "createCommitOnBranch", "commit"], "response"),
		"commit",
	);
	const signature = isRecord(commit.signature) ? commit.signature : undefined;
	const verification = signature
		? `${asString(signature.state, "signature state")}, signed by GitHub: ${signature.wasSignedByGitHub === true}`
		: "unsigned";
	return `${asString(commit.oid, "commit oid")} (${verification})`;
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
		console.log(
			`Committed ${await createCommit(token, commitInput(target, contents, changes.deletions))} on ${branch}.`,
		);
	} else {
		console.log("Selected paths match HEAD; no commit was created.");
	}
	const githubOutput = process.env.GITHUB_OUTPUT;
	if (isSet(githubOutput)) {
		await appendFile(githubOutput, `changed=${changed}\n`);
	}
}
