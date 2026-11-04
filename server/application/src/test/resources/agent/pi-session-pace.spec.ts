import assert from "node:assert/strict";
import { test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";

import {
	newModelWait,
	newStallRule,
	podMemoryAvailable,
	roomForSession,
	SessionPace,
} from "../../../main/resources/agent/pi-session-pace.ts";

const MIB = 1024 * 1024;
const open = new AbortController().signal;

async function started(pace: SessionPace): Promise<boolean> {
	const tooLate = new AbortController();
	const start = pace.nextStart(tooLate.signal);
	await sleep(30);
	tooLate.abort();
	return start;
}

void test("the first session starts whatever the room says", async () => {
	const pace = new SessionPace(() => false);
	assert.equal(await started(pace), true);
});

void test("a session holds the next start until a model call of it is served", async () => {
	const pace = new SessionPace(() => true);
	const first = newModelWait();
	pace.begin(first);
	assert.equal(await started(pace), false);

	const served = first.call();
	assert.equal(first.waitsForModel(), true);
	assert.equal(await started(pace), false);

	const next = pace.nextStart(open);
	served();
	assert.equal(first.waitsForModel(), false);
	assert.equal(await next, true);
});

void test("a slow script gives its start credit back", async () => {
	const pace = new SessionPace(() => true, 10);
	pace.begin(newModelWait());
	assert.equal(await pace.nextStart(open), true);
});

void test("any session that waits stops the next start, and service resumes it", async () => {
	const pace = new SessionPace(() => true);
	const sessions = [newModelWait(), newModelWait()];
	const served = sessions.map((session) => {
		pace.begin(session);
		return session.call();
	});
	served[0]?.();
	assert.equal(await started(pace), false);
	served[1]?.();
	assert.equal(await started(pace), true);
});

void test("a session that ended no longer holds a start, even if its call never answered", async () => {
	const pace = new SessionPace(() => true);
	const session = newModelWait();
	pace.begin(session);
	session.call();
	const next = pace.nextStart(open);
	pace.end(session);
	assert.equal(await next, true);
	assert.equal(pace.sessions, 0);
});

void test("no room stops the next start until a session ends", async () => {
	let room = false;
	const pace = new SessionPace(() => room);
	const [a, b] = [newModelWait(), newModelWait()];
	pace.begin(a);
	pace.begin(b);
	a.started();
	b.started();
	assert.equal(await started(pace), false);
	assert.equal(pace.heldForMemory > 0, true);
	const next = pace.nextStart(open);
	room = true;
	pace.end(a);
	assert.equal(await next, true);
});

void test("the wait for a start ends with the attempt", async () => {
	const pace = new SessionPace(() => true);
	pace.begin(newModelWait());
	const attempt = new AbortController();
	const next = pace.nextStart(attempt.signal);
	attempt.abort();
	assert.equal(await next, false);
});

function cgroup(files: Record<string, string>) {
	return (file: string) => {
		const content = files[file];
		if (content === undefined) {
			throw new Error(`no ${file}`);
		}
		return content;
	};
}

void test("memory left is the limit minus what the kernel cannot reclaim", () => {
	const read = cgroup({
		"memory.max": `${String(1280 * MIB)}\n`,
		"memory.current": `${String(500 * MIB)}\n`,
		"memory.stat": `anon 100\ninactive_file ${String(40 * MIB)}\nactive_file 7\n`,
	});
	assert.equal(podMemoryAvailable(read), 820 * MIB);
});

void test("no cgroup limit means that memory does not stop a start", () => {
	assert.equal(podMemoryAvailable(cgroup({ "memory.max": "max\n" })), Number.POSITIVE_INFINITY);
	assert.equal(podMemoryAvailable(cgroup({})), Number.POSITIVE_INFINITY);
});

void test("room needs memory for a script at its heap limit", () => {
	const heap = () => ({ used_heap_size: 100 * MIB, heap_size_limit: 256 * MIB });
	const room = (available: number) =>
		roomForSession({
			running: 4,
			scriptHeapBytes: 256 * MIB,
			baseHeapBytes: 80 * MIB,
			available: () => available,
			heap,
		});
	assert.equal(room(256 * MIB), true);
	assert.equal(room(255 * MIB), false);
});

void test("room needs heap for one more average session", () => {
	const room = (used: number) =>
		roomForSession({
			running: 10,
			scriptHeapBytes: 256 * MIB,
			baseHeapBytes: 56 * MIB,
			available: () => 1024 * MIB,
			heap: () => ({ used_heap_size: used, heap_size_limit: 256 * MIB }),
		});
	// 10 sessions hold 180 MiB above the base, so the next one needs 18 MiB.
	assert.equal(room(236 * MIB), true);
	// 10 sessions hold 200 MiB above the base, so the next one needs 20 MiB.
	assert.equal(room(256 * MIB), false);
});

void test("no session starts in less time than the sessions so far took", async () => {
	let now = 0;
	const pace = new SessionPace(
		() => true,
		10_000,
		() => now,
	);
	const first = newModelWait();
	pace.begin(first);
	first.started();
	assert.equal(await pace.nextStart(open, 1000), true, "no estimate before a session ends");
	now = 600;
	pace.end(first);
	assert.equal(pace.timeFor(1200), true);
	assert.equal(pace.timeFor(1199), false);
	assert.equal(await pace.nextStart(open, 1199), false);
});

void test("two unanswered sessions in a row end measuring, and an answered one resets the count", () => {
	const ends = newStallRule();
	assert.equal(ends("stall"), false);
	assert.equal(ends(null), false);
	assert.equal(ends("stall"), false);
	assert.equal(ends("budget"), false);
	assert.equal(ends("stall"), false);
	assert.equal(ends("stall"), true);
});
