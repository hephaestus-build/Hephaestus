/**
 * Queues the open Version PR at the front of the merge queue: release-management.mdx § The Version PR.
 * Nothing ahead of it can then merge changesets that it did not consume. Pull requests that join its
 * group behind it are fine, because the release follows the commit that set the version.
 *
 * A maintainer runs it; it is not a workflow step, because a maintainer account may jump the queue
 * and a workflow's GITHUB_TOKEN is not shown to. Each new head of the Version PR dismisses the earlier
 * approval, so the maintainer's run approves a head itself, but only one that maintenance made and
 * that changes only the version and its notes.
 *
 * - `--hold [--timeout-minutes 60]` takes every queued pull request out of the queue first, so `main`
 *   goes quiet. It waits until maintenance versions the quiet `main`, approves that head's waiting
 *   runs and the head itself, waits for its checks, queues it at the front, and queues the held pull
 *   requests behind it in their old order. A failure, the timeout or an interrupt queues them back.
 * - Without `--hold`, it makes one attempt for a Version PR that is already fresh and green.
 * - `--resume <file>` queues a held list that a killed run left.
 */
import { readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import { asArray, asRecord, asString, at, isRecord, parseJson } from "./lib/json.ts";
import { output, succeeds } from "./lib/process.ts";

import { VERSION_BRANCH } from "./maintain-version-pr.ts";

/** The check that `CI/CD` reports on a head once its own release evidence preflight passed. */
const STATUS_GATE = "CI Status Gate";

/** The account that maintenance commits the Version PR with. */
export const RELEASE_BOT = "github-actions[bot]";

/** What `vp run release:version` writes besides the consumed notes (scripts/sync-release-version.ts). */
const VERSIONED_FILES = new Set([
	"package.json",
	"CHANGELOG.md",
	"MIGRATION.md",
	"docs/admin/k3s.mdx",
]);
/** The versioned files whose content the check reads, beyond their paths. */
const IN_PLACE = ["package.json", "docs/admin/k3s.mdx"];
const NOTE = /^\.changeset\/[^/]+\.md$/u;
const FRAGMENT = /^\.migration\/[^/]+\.md$/u;

export interface QueueEntry {
	readonly id: string;
	readonly number: number;
	readonly headRefOid: string;
}

export type GateState = "success" | "pending" | "failure";

export interface VersionPullRequest extends QueueEntry {
	readonly approved: boolean;
	/** `CI Status Gate` at the exact head. */
	readonly checks: GateState;
}

export interface Change {
	readonly status: string;
	readonly path: string;
	/** The new path of a rename or copy. */
	readonly to?: string;
}

export interface VersionCommit {
	readonly pullRequestAuthor: string;
	readonly commitAuthor: string;
	/** `web-flow` when GitHub itself made the commit, through its API, for the bot. */
	readonly committer: string;
	/** GitHub's verification reason: `valid` only for a signature that GitHub verified. */
	readonly verification: string;
	/** The head has one parent, and that parent is a commit of `main`. */
	readonly onMain: boolean;
	readonly changes: readonly Change[];
	/** Both sides of the files whose content versioning edits in place. */
	readonly contents: Readonly<Record<string, { readonly before: string; readonly after: string }>>;
}

/** GitHub and git, behind an interface so that the hold can be tested without them. */
export interface QueueApi {
	readonly versionPullRequest: () => Promise<VersionPullRequest>;
	/** The queued pull requests, front first. */
	readonly queued: () => Promise<QueueEntry[]>;
	/** Whether the pull request changes the version against the commit it branched from. */
	readonly changesVersion: (entry: QueueEntry) => Promise<boolean>;
	/** The changesets that `main` added after the commit that `head` versions. */
	readonly unconsumed: (head: string) => Promise<string[]>;
	/** Approves the Version PR's own runs at its head that wait for approval; returns how many. */
	readonly approveRuns: (pullRequest: QueueEntry) => Promise<number>;
	/** Who made the Version PR and its head commit, and what that commit changed. */
	readonly versionCommit: (pullRequest: QueueEntry) => Promise<VersionCommit>;
	/** Approves the Version PR at its exact head. */
	readonly approve: (pullRequest: QueueEntry) => Promise<void>;
	readonly dequeue: (entry: QueueEntry) => Promise<void>;
	/** Returns the queue position. A jump also requires the head to be `entry.headRefOid`. */
	readonly enqueue: (entry: QueueEntry, jump: boolean) => Promise<unknown>;
	readonly saveHeld: (entries: readonly QueueEntry[]) => Promise<void>;
}

export interface QueueOptions {
	/** Takes the other pull requests out of the queue while the Version PR gets ready. */
	readonly hold: boolean;
	readonly timeoutMs: number;
	readonly pollMs: number;
	readonly signal?: AbortSignal;
	readonly now?: () => number;
	readonly sleep?: (milliseconds: number, signal?: AbortSignal) => Promise<void>;
	readonly log?: (line: string) => void;
	/** The queue that puts the held pull requests back, through calls that are never cancelled. */
	readonly restore?: QueueApi;
}

/** The changesets that `git diff --name-status -z` reports as added: notes the Version PR did not consume. */
export function addedChangesets(nameStatus: string): string[] {
	return parseChanges(nameStatus)
		.filter(
			(change) =>
				change.status === "A" && NOTE.test(change.path) && change.path !== ".changeset/README.md",
		)
		.map((change) => change.path);
}

/** A jump puts the entry at the front; anything else means GitHub ignored it. */
export function isAtFront(position: unknown): boolean {
	return position === 1;
}

/** `git diff --name-status -z --find-renames` output: a rename or copy carries two paths. */
export function parseChanges(text: string): Change[] {
	const fields = text.split("\0").filter((field) => field !== "");
	const changes: Change[] = [];
	for (let index = 0; index < fields.length;) {
		const status = fields[index] ?? "";
		const twoPaths = status.startsWith("R") || status.startsWith("C");
		changes.push({
			status,
			path: fields[index + 1] ?? "",
			...(twoPaths ? { to: fields[index + 2] ?? "" } : {}),
		});
		index += twoPaths ? 3 : 2;
	}
	return changes;
}

/** Whether `package.json` changed in its `version` field alone. */
export function onlyVersionField(before: string, after: string): boolean {
	try {
		const { version: _old, ...earlier } = asRecord(parseJson(before), "package.json before");
		const { version: _new, ...later } = asRecord(parseJson(after), "package.json after");
		return JSON.stringify(earlier) === JSON.stringify(later);
	} catch {
		return false;
	}
}

/** The line that scripts/sync-release-version.ts rewrites in docs/admin/k3s.mdx. */
const VERSION_LINE = /^[ \t]*VERSION=\S+\s+# the release, without the leading "v"/u;

const withoutVersion = (line: string) => line.replace(/VERSION=\S+/u, "VERSION=");

/** Whether the text changed in that one version line alone. */
export function onlyVersionLine(before: string, after: string): boolean {
	const earlier = before.split("\n");
	const later = after.split("\n");
	if (earlier.length !== later.length) {
		return false;
	}
	const changed = earlier.flatMap((line, index) => (line === later[index] ? [] : [index]));
	const [index] = changed;
	if (changed.length !== 1 || index === undefined) {
		return false;
	}
	const [old = "", next = ""] = [earlier[index], later[index]];
	return (
		VERSION_LINE.test(old) &&
		VERSION_LINE.test(next) &&
		withoutVersion(old) === withoutVersion(next)
	);
}

/** Whether versioning makes this change: the version, the changelogs, and the notes they consumed. */
function versioningChange(
	{ status, path: file, to }: Change,
	contents: VersionCommit["contents"],
): boolean {
	const content = contents[file];
	if (file === "package.json") {
		return (
			status === "M" && content !== undefined && onlyVersionField(content.before, content.after)
		);
	}
	if (file === "docs/admin/k3s.mdx") {
		return (
			status === "M" && content !== undefined && onlyVersionLine(content.before, content.after)
		);
	}
	if (VERSIONED_FILES.has(file)) {
		return status === "M";
	}
	// In pre mode a consumed note moves unchanged into `.changeset/pre/`; a stable version deletes it.
	if (NOTE.test(file) && file !== ".changeset/README.md") {
		return (
			status === "D" ||
			(status === "R100" && to === `.changeset/pre/${file.slice(".changeset/".length)}`)
		);
	}
	return FRAGMENT.test(file) && file !== ".migration/README.md" && status === "D";
}

/**
 * Why the Version PR's head is not one that versioning made, or `undefined` when it is. A maintainer
 * running the hold approves only a commit from maintenance that changes the version and its notes.
 */
export function unreviewable(commit: VersionCommit): string | undefined {
	if (commit.pullRequestAuthor !== RELEASE_BOT || commit.commitAuthor !== RELEASE_BOT) {
		return `its pull request is by ${commit.pullRequestAuthor} and its head commit by ${commit.commitAuthor}, not ${RELEASE_BOT}`;
	}
	// An author is only an e-mail address; GitHub's own commit through its API is what maintenance makes.
	if (commit.committer !== "web-flow" || commit.verification !== "valid") {
		return `its head commit is committed by ${commit.committer} with verification "${commit.verification}", not by GitHub for ${RELEASE_BOT}`;
	}
	// One commit on main: a commit below the head would merge without being read here.
	if (!commit.onMain) {
		return "its head is not one commit on a commit of main";
	}
	const other = commit.changes.filter((change) => !versioningChange(change, commit.contents));
	return other.length === 0
		? undefined
		: `its head commit also changes ${other.map((change) => `${change.status} ${change.path}`).join(", ")}`;
}

/**
 * The newest `CI Status Gate` run decides. A cancelled or skipped run is replaced by the run that
 * cancelled it, so it waits; only a failed or timed-out run fails.
 */
export function gateState(runs: readonly Record<string, unknown>[]): GateState {
	const newest = runs.toSorted((left, right) => Number(right.id) - Number(left.id))[0];
	if (newest === undefined) {
		return "pending";
	}
	if (newest.conclusion === "success") {
		return "success";
	}
	return newest.conclusion === "failure" || newest.conclusion === "timed_out"
		? "failure"
		: "pending";
}

/** The Version PR's own runs at its exact head that wait for approval: never a fork's or another branch's. */
export function ownWaitingRuns(
	runs: readonly Record<string, unknown>[],
	head: string,
	repository: string,
): Record<string, unknown>[] {
	return runs.filter(
		(run) =>
			run.head_sha === head &&
			run.head_branch === VERSION_BRANCH &&
			run.event === "pull_request" &&
			// GitHub reports a run that waits for approval as completed, with that conclusion.
			(run.status === "action_required" || run.conclusion === "action_required") &&
			isRecord(run.head_repository) &&
			run.head_repository.full_name === repository,
	);
}

/** A held list that `saveHeld` wrote. */
export function parseHeld(text: string): QueueEntry[] {
	return asArray(parseJson(text), "held pull requests").map((value) => {
		const entry = asRecord(value, "held pull request");
		return {
			id: asString(entry.id, "held id"),
			number: Number(entry.number),
			headRefOid: asString(entry.headRefOid, "held head"),
		};
	});
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error));
const asError = (error: unknown) => (error instanceof Error ? error : new Error(String(error)));

async function pause(milliseconds: number, signal?: AbortSignal): Promise<void> {
	await delay(milliseconds, undefined, { signal });
}

/** Approves the head's waiting runs and the head itself; the approvals do not wait for the checks. */
/** Throws unless the head is one that versioning made; each commit is read once. */
async function verifyHead(
	api: QueueApi,
	pullRequest: VersionPullRequest,
	verified: Set<string>,
): Promise<void> {
	const head = pullRequest.headRefOid;
	if (verified.has(head)) {
		return;
	}
	const reason = unreviewable(await api.versionCommit(pullRequest));
	if (reason !== undefined) {
		throw new Error(
			`#${pullRequest.number} at ${head} is not a Version PR to queue or approve: ${reason}`,
		);
	}
	verified.add(head);
}

/** Approves a verified head's waiting runs, then the head itself; neither waits for the checks. */
async function approveHead(
	api: QueueApi,
	pullRequest: VersionPullRequest,
	log: (line: string) => void,
): Promise<void> {
	const head = pullRequest.headRefOid;
	if (pullRequest.checks === "pending") {
		const runs = await api.approveRuns(pullRequest);
		if (runs > 0) {
			log(`Approved ${runs} waiting runs of #${pullRequest.number} at ${head}.`);
		}
	}
	if (!pullRequest.approved) {
		// Each new head dismisses the earlier approval, and nothing else approves a bot's pull request.
		await api.approve(pullRequest);
		log(`Approved #${pullRequest.number} at ${head}.`);
	}
}

/** Why the Version PR cannot enter the queue yet, or `undefined` when it can. */
async function blocker(
	api: QueueApi,
	pullRequest: VersionPullRequest,
	verified: Set<string>,
	log: (line: string) => void,
): Promise<string | undefined> {
	const head = pullRequest.headRefOid;
	const unconsumed = await api.unconsumed(head);
	if (unconsumed.length > 0) {
		return `main merged ${unconsumed.length} changesets after the commit that ${head.slice(0, 10)} versions (${unconsumed.join(", ")}); maintenance versions it again`;
	}
	if (pullRequest.checks === "failure") {
		throw new Error(`${STATUS_GATE} failed for #${pullRequest.number} at ${head}`);
	}
	// Every head before it is approved or queued: an approval or green checks say nothing about content.
	await verifyHead(api, pullRequest, verified);
	await approveHead(api, pullRequest, log);
	return pullRequest.checks === "success"
		? undefined
		: `${STATUS_GATE} has not passed for ${head.slice(0, 10)} yet`;
}

/** Where the entry stands in the queue: 1 at the front, `undefined` when it is not queued or unknown. */
async function positionOf(api: QueueApi, entry: QueueEntry): Promise<number | undefined> {
	try {
		const queued = await api.queued();
		const index = queued.findIndex((candidate) => candidate.id === entry.id);
		return index === -1 ? undefined : index + 1;
	} catch {
		return undefined;
	}
}

/** Takes the Version PR out of a place behind other pull requests, and says what happened. */
async function leaveBack(
	api: QueueApi,
	pullRequest: QueueEntry,
	position: unknown,
): Promise<never> {
	// Behind other pull requests, it could merge after changesets that it did not consume.
	try {
		await api.dequeue(pullRequest);
	} catch (error) {
		throw new Error(
			`#${pullRequest.number} is queued at position ${String(position)}, behind other pull requests, and could not be dequeued (${message(error)}); dequeue it by hand now`,
			{ cause: error },
		);
	}
	throw new Error(
		`GitHub queued #${pullRequest.number} at position ${String(position)}, not at the front, so the jump was not honoured; it is dequeued again`,
	);
}

/**
 * Queues the Version PR at the front. Returns false when its head moved before the jump, so the wait
 * goes on; throws when GitHub refused. The entry never stays behind other pull requests, even when
 * GitHub applied a jump whose answer was lost.
 */
async function jump(api: QueueApi, pullRequest: VersionPullRequest): Promise<boolean> {
	let position: unknown;
	try {
		position = await api.enqueue(pullRequest, true);
	} catch (error) {
		const landed = await positionOf(api, pullRequest);
		if (landed === 1) {
			return true;
		}
		if (landed !== undefined) {
			return leaveBack(api, pullRequest, landed);
		}
		let current = pullRequest;
		try {
			current = await api.versionPullRequest();
		} catch {
			// The refusal below says more than a failed re-read.
		}
		if (current.headRefOid !== pullRequest.headRefOid) {
			return false;
		}
		throw new Error(
			`GitHub refused to queue #${pullRequest.number} at the front (${message(error)}); run this as a maintainer, and never queue it at the back`,
			{ cause: error },
		);
	}
	return isAtFront(position) ? true : leaveBack(api, pullRequest, position);
}

/**
 * Puts the held pull requests back in their old order and returns those that GitHub refused. Held
 * pull requests that are still queued ahead of the first one that is not stay where they are; any
 * still queued behind it go out and back in, so the old order holds after a partial hold.
 */
export async function restore(
	api: QueueApi,
	held: readonly QueueEntry[],
	log: (line: string) => void,
): Promise<string[]> {
	let queued = new Set<string>();
	try {
		const entries = await api.queued();
		queued = new Set(entries.map((entry) => entry.id));
	} catch {
		// Unknown: every held pull request is queued again, and GitHub names those still queued.
	}
	const first = held.findIndex((entry) => !queued.has(entry.id));
	if (first === -1) {
		return [];
	}
	const refused: string[] = [];
	const back: QueueEntry[] = [];
	for (const entry of held.slice(first)) {
		if (!queued.has(entry.id)) {
			back.push(entry);
			continue;
		}
		try {
			await api.dequeue(entry);
			back.push(entry);
		} catch (error) {
			log(`#${entry.number} stays queued, ahead of its old place (${message(error)}).`);
		}
	}
	for (const entry of back) {
		try {
			await api.enqueue(entry, false);
			log(`Queued #${entry.number} again.`);
		} catch (error) {
			refused.push(`#${entry.number}: ${message(error)}`);
		}
	}
	return refused;
}

/** The checks before anything changes: the head, an earlier queue entry, a version change ahead. */
async function preflight(api: QueueApi, verified: Set<string>): Promise<QueueEntry[]> {
	const start = await api.versionPullRequest();
	await verifyHead(api, start, verified);
	const queued = await api.queued();
	if (queued.some((entry) => entry.id === start.id)) {
		throw new Error(`Version PR #${start.number} is already queued`);
	}
	for (const entry of queued) {
		if (await api.changesVersion(entry)) {
			throw new Error(
				`#${entry.number} in the queue changes the version; let it merge or dequeue it first`,
			);
		}
	}
	return queued;
}

/** Waits until the Version PR is fresh, approved and green, then queues it at the front. */
async function waitAndJump(
	api: QueueApi,
	options: QueueOptions,
	verified: Set<string>,
): Promise<void> {
	const log = options.log ?? console.log;
	const now = options.now ?? Date.now;
	const sleep = options.sleep ?? pause;
	const deadline = now() + options.timeoutMs;
	for (;;) {
		options.signal?.throwIfAborted();
		const pullRequest = await api.versionPullRequest();
		const reason = await blocker(api, pullRequest, verified, log);
		if (reason === undefined && (await jump(api, pullRequest))) {
			log(`Queued Version PR #${pullRequest.number} at the front of the merge queue.`);
			return;
		}
		const waiting = reason ?? `the head of #${pullRequest.number} moved before the jump`;
		if (now() + options.pollMs > deadline) {
			throw new Error(
				options.timeoutMs === 0
					? `not ready: ${waiting}`
					: `gave up after ${Math.round(options.timeoutMs / 60_000)} min: ${waiting}`,
			);
		}
		log(`Waiting: ${waiting}.`);
		await sleep(options.pollMs, options.signal);
	}
}

export async function queueVersionPullRequest(api: QueueApi, options: QueueOptions): Promise<void> {
	const log = options.log ?? console.log;
	const verified = new Set<string>();
	const queued = await preflight(api, verified);
	const held = options.hold ? queued : [];
	if (held.length > 0) {
		// Recorded before the first dequeue, so even a killed run leaves the list behind.
		await api.saveHeld(held);
	}
	let failure: unknown;
	try {
		for (const entry of held) {
			options.signal?.throwIfAborted();
			await api.dequeue(entry);
			log(`Held #${entry.number}.`);
		}
		await waitAndJump(api, options, verified);
	} catch (error) {
		failure = error;
	}
	// Through its own queue: the forward calls may be cancelled, and putting the queue back must not be.
	const refused = held.length > 0 ? await restore(options.restore ?? api, held, log) : [];
	if (refused.length > 0) {
		const lost = `could not queue again: ${refused.join("; ")}`;
		throw new Error(failure === undefined ? lost : `${message(failure)}; ${lost}`, {
			cause: failure,
		});
	}
	if (failure !== undefined) {
		throw asError(failure);
	}
}

/** `main` and the exact head: maintenance may move the branch between the listing and the fetch. */
async function fetchMainAnd(head: string, signal?: AbortSignal): Promise<void> {
	await output(
		"git",
		["fetch", "--no-tags", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main", head],
		{ signal },
	);
}

/** A file at a revision, or "" where the revision has none. */
async function showFile(revision: string, file: string, signal?: AbortSignal): Promise<string> {
	try {
		return await output("git", ["show", `${revision}:${file}`], { signal });
	} catch {
		return "";
	}
}

async function exists(file: string): Promise<boolean> {
	try {
		await readFile(file, "utf8");
		return true;
	} catch {
		return false;
	}
}

async function graphqlRequest(
	query: string,
	fields: Record<string, string>,
	signal?: AbortSignal,
): Promise<unknown> {
	const args = ["api", "graphql", "-f", `query=${query}`];
	for (const [key, value] of Object.entries(fields)) {
		args.push("-f", `${key}=${value}`);
	}
	return parseJson(await output("gh", args, { signal }));
}

/** The repository's queue, through the maintainer's `gh` login and the local clone. */
async function githubQueue(heldFile: string, signal?: AbortSignal): Promise<QueueApi> {
	const call = async (command: string, args: string[]) => output(command, args, { signal });
	const viewed = await call("gh", [
		"repo",
		"view",
		"--json",
		"nameWithOwner",
		"--jq",
		".nameWithOwner",
	]);
	const repository = viewed.trim();
	const [owner = "", name = ""] = repository.split("/");
	const versionAt = async (ref: string) => {
		const content = await call("gh", [
			"api",
			`repos/${repository}/contents/package.json?ref=${ref}`,
			"--jq",
			".content",
		]);
		const manifest = asRecord(
			parseJson(Buffer.from(content, "base64").toString("utf8")),
			"package.json",
		);
		return asString(manifest.version, "package.json version");
	};
	const gate = async (head: string) => {
		const runs = await call("gh", [
			"api",
			`repos/${repository}/commits/${head}/check-runs?check_name=${encodeURIComponent(STATUS_GATE)}`,
			"--jq",
			".check_runs",
		]);
		return gateState(
			asArray(parseJson(runs), "check runs").map((run) => asRecord(run, "check run")),
		);
	};
	return {
		versionPullRequest: async () => {
			const listed = await call("gh", [
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
				"id,number,headRefOid,reviewDecision",
			]);
			const entries = asArray(parseJson(listed), "open Version PRs");
			if (entries.length !== 1) {
				throw new Error(
					`expected one open Version PR from ${VERSION_BRANCH}, found ${entries.length}`,
				);
			}
			const pullRequest = asRecord(entries[0], "Version PR");
			const headRefOid = asString(pullRequest.headRefOid, "Version PR head");
			return {
				id: asString(pullRequest.id, "Version PR id"),
				number: Number(pullRequest.number),
				headRefOid,
				approved: pullRequest.reviewDecision === "APPROVED",
				checks: await gate(headRefOid),
			};
		},
		queued: async () => {
			const response = await graphqlRequest(
				'query($owner: String!, $name: String!) { repository(owner: $owner, name: $name) { mergeQueue(branch: "main") { entries(first: 100) { nodes { position pullRequest { id number headRefOid } } } } } }',
				{ owner, name },
				signal,
			);
			return asArray(
				at(response, ["data", "repository", "mergeQueue", "entries", "nodes"], "merge queue"),
				"entries",
			)
				.map((value) => asRecord(value, "entry"))
				.toSorted((left, right) => Number(left.position) - Number(right.position))
				.map((entry) => {
					const pullRequest = asRecord(entry.pullRequest, "queued pull request");
					return {
						id: asString(pullRequest.id, "pull request id"),
						number: Number(pullRequest.number),
						headRefOid: asString(pullRequest.headRefOid, "pull request head"),
					};
				});
		},
		changesVersion: async (entry) => {
			// Against the commit it branched from: an older branch still carries the older version.
			const base = await call("gh", [
				"api",
				`repos/${repository}/compare/main...${entry.headRefOid}`,
				"--jq",
				".merge_base_commit.sha",
			]);
			return (await versionAt(entry.headRefOid)) !== (await versionAt(base.trim()));
		},
		unconsumed: async (head) => {
			await fetchMainAnd(head, signal);
			// The Version PR is one commit on the main commit it versions.
			const parent = await call("git", ["rev-parse", `${head}^`]);
			const changed = await call("git", [
				"diff",
				"--name-status",
				"-z",
				"--no-renames",
				parent.trim(),
				"origin/main",
				"--",
				".changeset",
			]);
			return addedChangesets(changed);
		},
		approveRuns: async (pullRequest) => {
			const listed = await call("gh", [
				"api",
				`repos/${repository}/actions/runs?head_sha=${pullRequest.headRefOid}&status=action_required&per_page=100`,
				"--jq",
				".workflow_runs",
			]);
			const runs = ownWaitingRuns(
				asArray(parseJson(listed), "workflow runs").map((run) => asRecord(run, "workflow run")),
				pullRequest.headRefOid,
				repository,
			);
			for (const run of runs) {
				await call("gh", [
					"api",
					"--method",
					"POST",
					`repos/${repository}/actions/runs/${String(run.id)}/approve`,
				]);
			}
			return runs.length;
		},
		versionCommit: async (pullRequest) => {
			const author = await call("gh", [
				"api",
				`repos/${repository}/pulls/${String(pullRequest.number)}`,
				"--jq",
				".user.login",
			]);
			const commit = asRecord(
				parseJson(
					await call("gh", ["api", `repos/${repository}/commits/${pullRequest.headRefOid}`]),
				),
				"head commit",
			);
			const head = pullRequest.headRefOid;
			await fetchMainAnd(head, signal);
			const changed = await call("git", [
				"diff",
				"--name-status",
				"-z",
				"--find-renames",
				`${head}^`,
				head,
			]);
			const parents = await call("git", ["rev-list", "--parents", "--max-count=1", head]);
			const contents: Record<string, { before: string; after: string }> = {};
			for (const file of IN_PLACE) {
				contents[file] = {
					before: await showFile(`${head}^`, file, signal),
					after: await showFile(head, file, signal),
				};
			}
			return {
				pullRequestAuthor: author.trim(),
				commitAuthor: isRecord(commit.author) ? String(commit.author.login) : "",
				committer: isRecord(commit.committer) ? String(commit.committer.login) : "",
				verification: String(at(commit, ["commit", "verification", "reason"], "verification")),
				onMain:
					parents.trim().split(" ").length === 2 &&
					(await succeeds("git", ["merge-base", "--is-ancestor", `${head}^`, "origin/main"], {
						signal,
					})),
				changes: parseChanges(changed),
				contents,
			};
		},
		approve: async (pullRequest) => {
			await call("gh", [
				"api",
				"--method",
				"POST",
				`repos/${repository}/pulls/${String(pullRequest.number)}/reviews`,
				"-f",
				"event=APPROVE",
				"-f",
				`commit_id=${pullRequest.headRefOid}`,
				"-f",
				"body=Approved by queue-version-pr.ts: maintenance versioned this head, and it changes only the version and its notes.",
			]);
		},
		dequeue: async (entry) => {
			await graphqlRequest(
				"mutation($id: ID!) { dequeuePullRequest(input: { id: $id }) { clientMutationId } }",
				{ id: entry.id },
				signal,
			);
		},
		enqueue: async (entry, front) => {
			const response = front
				? await graphqlRequest(
						"mutation($id: ID!, $head: GitObjectID!) { enqueuePullRequest(input: { pullRequestId: $id, expectedHeadOid: $head, jump: true }) { mergeQueueEntry { position } } }",
						{ id: entry.id, head: entry.headRefOid },
						signal,
					)
				: await graphqlRequest(
						"mutation($id: ID!) { enqueuePullRequest(input: { pullRequestId: $id }) { mergeQueueEntry { position } } }",
						{ id: entry.id },
						signal,
					);
			return at(
				response,
				["data", "enqueuePullRequest", "mergeQueueEntry", "position"],
				"queue entry",
			);
		},
		saveHeld: async (entries) => {
			await writeFile(heldFile, `${JSON.stringify(entries, undefined, "\t")}\n`);
			console.log(`Held pull requests: ${heldFile}`);
			console.log(
				`If this run is killed, queue them again with: node scripts/queue-version-pr.ts --resume ${heldFile}`,
			);
		},
	};
}

/** The command line: `--hold`, `--timeout-minutes <n>`, `--held-file <file>`, `--resume <file>`. */
export function parseArguments(args: readonly string[]): {
	hold: boolean;
	timeoutMinutes: number;
	heldFile?: string;
	resume?: string;
} {
	const parsed: { hold: boolean; timeoutMinutes: number; heldFile?: string; resume?: string } = {
		hold: false,
		timeoutMinutes: 60,
	};
	for (let index = 0; index < args.length; index += 1) {
		const flag = args[index];
		const value = args[index + 1];
		if (flag === "--hold") {
			parsed.hold = true;
			continue;
		}
		if (value === undefined || value.startsWith("--")) {
			throw new Error(`${String(flag)} needs a value, or is not an option of queue-version-pr.ts`);
		}
		index += 1;
		if (flag === "--timeout-minutes") {
			const minutes = Number(value);
			if (!Number.isFinite(minutes) || minutes <= 0) {
				throw new Error(`--timeout-minutes must be a positive number of minutes, not "${value}"`);
			}
			parsed.timeoutMinutes = minutes;
		} else if (flag === "--held-file") {
			parsed.heldFile = value;
		} else if (flag === "--resume") {
			parsed.resume = value;
		} else {
			throw new Error(`${String(flag)} is not an option of queue-version-pr.ts`);
		}
	}
	return parsed;
}

/** Margin for one stalled `git` or `gh` call past the deadline before it is cancelled. */
const STALL_MS = 5 * 60_000;

async function main(
	heldFile: string,
	parsed: ReturnType<typeof parseArguments>,
	signal: AbortSignal,
) {
	// Putting the queue back never shares a cancelled signal with the calls it recovers from.
	const restoring = await githubQueue(heldFile);
	if (parsed.resume !== undefined) {
		const refused = await restore(
			restoring,
			parseHeld(await readFile(parsed.resume, "utf8")),
			console.log,
		);
		if (refused.length > 0) {
			throw new Error(`could not queue again: ${refused.join("; ")}`);
		}
		return;
	}
	// Without a hold, the queue keeps moving: one attempt, no wait.
	const timeoutMs = parsed.hold ? parsed.timeoutMinutes * 60_000 : 0;
	// An interrupt or the deadline also cancels a `git` or `gh` call that stalls in flight.
	const forward = AbortSignal.any([signal, AbortSignal.timeout(timeoutMs + STALL_MS)]);
	await queueVersionPullRequest(await githubQueue(heldFile, forward), {
		hold: parsed.hold,
		timeoutMs,
		pollMs: 30_000,
		signal: forward,
		restore: restoring,
	});
}

if (import.meta.main) {
	const controller = new AbortController();
	// The first signal stops the wait and queues the held pull requests again; a second one kills.
	for (const name of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
		process.once(name, () => {
			console.log(`::warning::${name}: queueing the held pull requests again`);
			controller.abort();
		});
	}
	let heldFile = "";
	try {
		const parsed = parseArguments(process.argv.slice(2));
		heldFile =
			parsed.heldFile ??
			path.join(
				tmpdir(),
				`hephaestus-held-queue-${new Date().toISOString().replaceAll(":", "")}.json`,
			);
		await main(heldFile, parsed, controller.signal);
	} catch (error) {
		console.log(`::error::${message(error)}`);
		if (heldFile !== "" && (await exists(heldFile))) {
			console.log(
				`If a held pull request is still out of the queue: node scripts/queue-version-pr.ts --resume ${heldFile}`,
			);
		}
		process.exitCode = 1;
	}
}
