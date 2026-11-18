/**
 * Queues the open Version PR at the front of the merge queue: release-management.mdx § The Version PR.
 * Nothing ahead of it can then merge changesets that it did not consume. Pull requests that join its
 * group behind it are fine, because the release follows the commit that set the version.
 * A maintainer runs it after approving the Version PR: `node scripts/queue-version-pr.ts`. It is not
 * a workflow step: a maintainer account may jump, and a workflow's GITHUB_TOKEN is not shown to.
 */
import { asRecord, asString, at, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

import { VERSION_BRANCH } from "./maintain-version-pr.ts";

/** The changesets that `git diff --name-status` reports as added: notes the Version PR did not consume. */
export function addedChangesets(nameStatus: string): string[] {
	return nameStatus
		.split("\n")
		.map((line) => line.split("\t"))
		.filter(
			([status, file = ""]) =>
				status === "A" && /^\.changeset\/[^/]+\.md$/u.test(file) && file !== ".changeset/README.md",
		)
		.map(([, file = ""]) => file);
}

async function queueVersionPullRequest(): Promise<void> {
	const viewed = await output("gh", [
		"repo",
		"view",
		"--json",
		"nameWithOwner",
		"--jq",
		".nameWithOwner",
	]);
	const repository = viewed.trim();
	const listed = parseJson(
		await output("gh", [
			"pr",
			"list",
			"--repo",
			repository,
			"--head",
			VERSION_BRANCH,
			"--base",
			"main",
			"--state",
			"open",
			"--json",
			"id,number,headRefOid",
		]),
	);
	if (!Array.isArray(listed) || listed.length !== 1) {
		throw new Error(`expected one open Version PR from ${VERSION_BRANCH}`);
	}
	const pullRequest = asRecord(listed[0], "Version PR");
	const head = asString(pullRequest.headRefOid, "Version PR head");
	await output("git", [
		"fetch",
		"--no-tags",
		"--quiet",
		"origin",
		"+refs/heads/main:refs/remotes/origin/main",
		`+refs/heads/${VERSION_BRANCH}:refs/remotes/origin/${VERSION_BRANCH}`,
	]);
	// The Version PR is one commit on the main commit it versions.
	const parent = await output("git", ["rev-parse", `${head}^`]);
	const base = parent.trim();
	const added = addedChangesets(
		await output("git", [
			"diff",
			"--name-status",
			"--no-renames",
			base,
			"origin/main",
			"--",
			".changeset",
		]),
	);
	if (added.length > 0) {
		throw new Error(
			`main merged ${added.length} changesets after the Version PR was versioned (${added.join(", ")}); wait until maintenance versions it again`,
		);
	}
	const id = asString(pullRequest.id, "Version PR id");
	let response: unknown;
	try {
		response = parseJson(
			await output("gh", [
				"api",
				"graphql",
				"-f",
				"query=mutation($id: ID!, $head: GitObjectID!) { enqueuePullRequest(input: { pullRequestId: $id, expectedHeadOid: $head, jump: true }) { mergeQueueEntry { position } } }",
				"-f",
				`id=${id}`,
				"-f",
				`head=${head}`,
			]),
		);
	} catch (error) {
		throw new Error(
			`GitHub refused to queue the Version PR at the front (${error instanceof Error ? error.message : String(error)}); run this as a maintainer, and never queue it at the back`,
			{ cause: error },
		);
	}
	const position = at(
		response,
		["data", "enqueuePullRequest", "mergeQueueEntry", "position"],
		"queue entry",
	);
	if (!isAtFront(position)) {
		// Behind other pull requests, it could merge after changesets that it did not consume.
		await output("gh", [
			"api",
			"graphql",
			"-f",
			"query=mutation($id: ID!) { dequeuePullRequest(input: { id: $id }) { clientMutationId } }",
			"-f",
			`id=${id}`,
		]);
		throw new Error(
			`GitHub queued the Version PR at position ${String(position)}, not at the front, so the jump was not honoured; it is dequeued again. Run this as a maintainer`,
		);
	}
	console.log(`Queued Version PR #${String(pullRequest.number)} at the front of the merge queue.`);
}

/** A jump puts the entry at the front; anything else means GitHub ignored it. */
export function isAtFront(position: unknown): boolean {
	return position === 1;
}

if (import.meta.main) {
	try {
		await queueVersionPullRequest();
	} catch (error) {
		console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
