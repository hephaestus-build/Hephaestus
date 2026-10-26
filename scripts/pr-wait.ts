/**
 * Watch one pull request and print one line per new event, until it is ready to merge.
 *
 *   vp run pr:wait <pr> [--repo owner/name] [--interval 30]
 *
 * Stdout carries only events, so a monitor (Claude Code's Monitor tool) wakes the agent once per
 * event. An event names its kind, author and URL, never the text: comments come from anyone who can
 * comment, and the agent reads them through the URL as data. `.claude/skills/watch-pr/SKILL.md` says
 * when to run it. Comments by the pull request's author and by the `gh` user are not events.
 *
 * Exit codes: 0 ready or merged; 1 only action can make it ready (a failed required check, a
 * conflict, changes requested, an unresolved thread or a draft on a green head, closed, or GitHub
 * refused); 2 bad arguments, an unknown pull request, or no repository.
 *
 * Adapted from `pr-wait` in tstack (https://github.com/t3dotgg/tstack, MIT, © 2026 Theo Browne).
 */
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

export interface Check {
	readonly name: string;
	readonly state: "pass" | "fail" | "pending";
	readonly required: boolean;
	readonly url: string;
}

export interface Note {
	readonly id: string;
	readonly kind: "comment" | "review" | "thread" | "reply";
	readonly author: string;
	readonly url: string;
}

export interface Snapshot {
	readonly state: string;
	readonly isDraft: boolean;
	readonly url: string;
	readonly head: string;
	readonly mergeState: string;
	readonly reviewDecision: string;
	readonly mine: ReadonlySet<string>;
	readonly notes: readonly Note[];
	readonly unresolved: readonly string[];
	readonly checks: readonly Check[];
}

/** What earlier polls saw. The first poll records what exists and reports nothing. */
export interface Memory {
	readonly seen: ReadonlySet<string>;
	readonly first: boolean;
	readonly checks?: string;
}

export interface Verdict {
	readonly lines: readonly string[];
	readonly memory: Memory;
	readonly exit?: 0 | 1;
}

// CI and deploy bots report through checks, so their comments repeat what a check already says.
const STATUS_BOTS = ["github-actions", "vercel", "netlify", "cloudflare"];

/** The decision once the required checks are green on two equal polls. */
function onGreen(pr: Snapshot): { exit?: 0 | 1; line: string } {
	const short = pr.head.slice(0, 7);
	const summary = `review ${pr.reviewDecision.toLowerCase() || "none"}`;
	if (pr.isDraft) {
		return { exit: 1, line: `draft on green ${short}: mark it ready for review` };
	}
	if (pr.reviewDecision === "CHANGES_REQUESTED") {
		return { exit: 1, line: `changes requested on ${short}` };
	}
	if (pr.unresolved.length > 0) {
		return { exit: 1, line: `unresolved threads on green ${short}: ${pr.unresolved.join(" ")}` };
	}
	return pr.reviewDecision === "APPROVED"
		? { exit: 0, line: `ready on ${short}: ${summary}` }
		: { line: `required checks green on ${short}: ${summary}` };
}

/** What one poll means, given what earlier polls saw. Pure, so every rule is testable. */
export function evaluate(pr: Snapshot, memory: Memory): Verdict {
	const seen = new Set(memory.seen);
	const lines: string[] = [];
	const emit = (key: string, line: string): void => {
		if (!seen.has(key)) {
			seen.add(key);
			if (!memory.first) {
				lines.push(line);
			}
		}
	};
	const states = pr.checks
		.map((check) => `${check.name}=${check.state}`)
		.toSorted()
		.join(",");
	const next: Memory = { seen, first: false, checks: states };
	const stop = (exit: 0 | 1, line: string): Verdict => ({
		lines: lines.includes(line) ? lines : [...lines, line],
		memory: next,
		exit,
	});

	if (pr.state !== "OPEN") {
		return pr.state === "MERGED"
			? stop(0, `merged: ${pr.url}`)
			: stop(1, `closed without merging: ${pr.url}`);
	}
	for (const note of pr.notes) {
		const statusBot = STATUS_BOTS.some((bot) => note.author.toLowerCase().startsWith(bot));
		if (!pr.mine.has(note.author) && !(statusBot && note.kind === "comment")) {
			emit(`note:${note.id}`, `${note.kind} from ${note.author}: ${note.url}`);
		}
	}
	const required = pr.checks.filter((check) => check.required);
	const failed = required.find((check) => check.state === "fail");
	if (pr.mergeState === "DIRTY") {
		return stop(1, "conflicts with its base: rebase it");
	}
	if (failed !== undefined) {
		return stop(1, `required check failed: ${failed.name} ${failed.url}`.trimEnd());
	}
	// A check registers a few seconds after a push, so only two equal polls call the checks done.
	const done =
		required.length > 0 &&
		required.every((check) => check.state === "pass") &&
		states === memory.checks;
	if (!done) {
		return { lines, memory: next };
	}
	const green = onGreen(pr);
	if (green.exit !== undefined) {
		return stop(green.exit, green.line);
	}
	emit(`green:${pr.head}`, green.line);
	return { lines, memory: next };
}

const QUERY = `query($owner: String!, $name: String!, $number: Int!) {
  viewer { login }
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      url state isDraft headRefOid mergeStateStatus reviewDecision author { login }
      commits(last: 1) { totalCount nodes { commit { statusCheckRollup { contexts(first: 100) { totalCount nodes {
        ... on CheckRun { name status conclusion detailsUrl isRequired(pullRequestNumber: $number) }
        ... on StatusContext { context state targetUrl isRequired(pullRequestNumber: $number) }
      } } } } } }
      comments(last: 100) { totalCount nodes { id url author { login } } }
      reviews(last: 100) { totalCount nodes { id url state body author { login } } }
      reviewThreads(last: 100) {
        totalCount nodes { isResolved comments(first: 100) { totalCount nodes { id url author { login } } } }
      }
    }
  }
}`;

const login = (node: Record<string, unknown>): string =>
	isRecord(node.author) && typeof node.author.login === "string" ? node.author.login : "ghost";

const text = (value: unknown): string => (typeof value === "string" ? value : "");

/** A connection's nodes. A list longer than one page could hide an unresolved thread, so it stops. */
function nodes(value: unknown, label: string, page = true): Record<string, unknown>[] {
	const connection = asRecord(value, label);
	const list = asArray(connection.nodes, `${label}.nodes`).map((node) => asRecord(node, label));
	if (page && typeof connection.totalCount === "number" && connection.totalCount > list.length) {
		throw new Error(`${label} has ${connection.totalCount} entries, more than one page`);
	}
	return list;
}

function statusState(state: string): Check["state"] {
	if (state === "SUCCESS") {
		return "pass";
	}
	return state === "PENDING" || state === "EXPECTED" ? "pending" : "fail";
}

const conclusionState = (conclusion: string): Check["state"] =>
	["SUCCESS", "NEUTRAL", "SKIPPED"].includes(conclusion) ? "pass" : "fail";

function toCheck(node: Record<string, unknown>): Check {
	const required = node.isRequired === true;
	if (typeof node.context === "string") {
		return {
			name: node.context,
			state: statusState(text(node.state)),
			required,
			url: text(node.targetUrl),
		};
	}
	const state = node.status === "COMPLETED" ? conclusionState(text(node.conclusion)) : "pending";
	return { name: asString(node.name, "check name"), state, required, url: text(node.detailsUrl) };
}

/** The pull request in one GraphQL response, validated at the trust boundary. */
export function parse(response: unknown): Snapshot {
	const data = asRecord(asRecord(response, "response").data, "data");
	const pr = asRecord(asRecord(data.repository, "repository").pullRequest, "pullRequest");
	const note = (node: Record<string, unknown>, kind: Note["kind"]): Note => ({
		id: asString(node.id, "id"),
		kind,
		author: login(node),
		url: asString(node.url, "url"),
	});
	const notes = nodes(pr.comments, "comments").map((node) => note(node, "comment"));
	for (const node of nodes(pr.reviews, "reviews")) {
		// An empty COMMENTED review only wraps thread comments, which are reported on their own.
		if (node.state !== "COMMENTED" || text(node.body).trim() !== "") {
			notes.push(note(node, "review"));
		}
	}
	const unresolved: string[] = [];
	for (const thread of nodes(pr.reviewThreads, "reviewThreads")) {
		const comments = nodes(thread.comments, "thread comments");
		const [first] = comments;
		if (thread.isResolved !== true && first !== undefined) {
			unresolved.push(asString(first.url, "url"));
		}
		for (const [index, node] of comments.entries()) {
			notes.push(note(node, index === 0 ? "thread" : "reply"));
		}
	}
	const [last] = nodes(pr.commits, "commits", false);
	const rollup = isRecord(last?.commit) ? last.commit.statusCheckRollup : undefined;
	return {
		state: asString(pr.state, "state"),
		isDraft: pr.isDraft === true,
		url: asString(pr.url, "url"),
		head: asString(pr.headRefOid, "headRefOid"),
		mergeState: asString(pr.mergeStateStatus, "mergeStateStatus"),
		reviewDecision: text(pr.reviewDecision),
		mine: new Set([login(pr), asString(asRecord(data.viewer, "viewer").login, "viewer")]),
		notes,
		unresolved,
		checks: isRecord(rollup) ? nodes(rollup.contexts, "checks").map(toCheck) : [],
	};
}

/** A pull request number, `#number`, or URL, and the repository a URL names. */
export function parseTarget(raw: string): { readonly number: number; readonly repo?: string } {
	const url = /^https?:\/\/github\.com\/(?<repo>[^/\s]+\/[^/\s]+)\/pull\/(?<number>\d+)/u.exec(raw);
	const number = Number((url ?? /^#?(?<number>\d+)$/u.exec(raw.trim()))?.groups?.number);
	if (!Number.isSafeInteger(number) || number <= 0) {
		throw new Error(`"${raw}" is not a pull request number or URL`);
	}
	const repo = url?.groups?.repo;
	return repo === undefined ? { number } : { number, repo };
}

/** `gh`, failing with the first line it printed on stderr, never the argv that Node echoes. */
async function gh(args: string[]): Promise<string> {
	try {
		return await output("gh", args);
	} catch (error) {
		const stderr = isRecord(error) ? text(error.stderr).trim() : "";
		throw new Error(stderr.split("\n")[0] ?? "gh failed", { cause: error });
	}
}

function quit(code: 0 | 1 | 2, line: string): never {
	console.log(`pr-wait: ${line}`);
	process.exit(code);
}

const messageOf = (error: unknown): string =>
	error instanceof Error ? error.message : String(error);

async function poll(repo: string, number: number): Promise<Snapshot> {
	const [owner = "", name = ""] = repo.split("/");
	try {
		const query = ["-f", `query=${QUERY}`, "-f", `owner=${owner}`, "-f", `name=${name}`];
		return parse(parseJson(await gh(["api", "graphql", ...query, "-F", `number=${number}`])));
	} catch (error) {
		const message = messageOf(error);
		if (/Could not resolve to a (?:PullRequest|Repository)/u.test(message)) {
			quit(2, message);
		}
		if (/rate limit/iu.test(message)) {
			const reset = await gh(["api", "rate_limit", "--jq", ".resources.graphql.reset"]).catch(
				() => "0",
			);
			quit(1, `GitHub rate limit; it resets at ${new Date(Number(reset) * 1000).toISOString()}`);
		}
		throw error;
	}
}

async function watch(repo: string, number: number, interval: number): Promise<never> {
	let memory: Memory = { seen: new Set(), first: true };
	let failures = 0;
	for (;;) {
		try {
			const verdict = evaluate(await poll(repo, number), memory);
			failures = 0;
			for (const line of verdict.lines) {
				console.log(`#${number} ${line}`);
			}
			if (verdict.exit !== undefined) {
				process.exit(verdict.exit);
			}
			memory = verdict.memory;
		} catch (error) {
			failures += 1;
			console.error(`pr-wait: poll failed (${failures}/5): ${messageOf(error)}`);
			if (failures >= 5) {
				quit(1, `stopped after 5 failed polls: ${messageOf(error)}`);
			}
		}
		await sleep(interval * 1000);
	}
}

if (process.argv[1] === import.meta.filename) {
	const usage = "usage: vp run pr:wait <pr> [--repo owner/name] [--interval 30]";
	let parsed;
	let target: ReturnType<typeof parseTarget>;
	try {
		parsed = parseArgs({
			allowPositionals: true,
			options: { repo: { type: "string" }, interval: { type: "string", default: "30" } },
		});
		target = parseTarget(parsed.positionals[0] ?? "");
	} catch (error) {
		quit(2, `${messageOf(error)}. ${usage}`);
	}
	const repo =
		parsed.values.repo ??
		target.repo ??
		(await gh(["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"]).catch(
			(error: unknown) => quit(2, `no --repo, and no GitHub repository here: ${messageOf(error)}`),
		));
	// Every watcher on an account shares its rate limit, so polls stay at least 15 seconds apart.
	await watch(repo.trim(), target.number, Math.max(Number(parsed.values.interval) || 30, 15));
}
