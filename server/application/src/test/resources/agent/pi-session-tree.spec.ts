import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { SessionManager } from "@earendil-works/pi-coding-agent";

import { forkSessions } from "../../../main/resources/agent/pi-session-tree.ts";

function assistantMessage(text: string) {
	return {
		role: "assistant" as const,
		content: [{ type: "text" as const, text }],
		api: "openai-completions" as const,
		provider: "test",
		model: "test",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop" as const,
		timestamp: Date.now(),
	};
}

void test("forks the same persisted checkpoint into independent session branches", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-session-tree-"));
	try {
		const sessionDir = join(root, "sessions");
		const seed = SessionManager.create(root, sessionDir);
		seed.appendMessage({
			role: "user",
			content: [{ type: "text", text: "Gather group evidence" }],
			timestamp: Date.now(),
		});
		const checkpointEntryId = seed.appendMessage(assistantMessage("Evidence manifest"));
		const seedSessionFile = seed.getSessionFile();
		assert.ok(seedSessionFile);

		const forks = forkSessions({
			seedSessionFile,
			checkpointEntryId,
			keys: ["group-a", "group-b"],
			sessionDir,
		});

		assert.equal(forks.length, 2);
		assert.notEqual(forks[0]?.sessionFile, forks[1]?.sessionFile);
		for (const fork of forks) {
			const forked = SessionManager.open(fork.sessionFile, sessionDir);
			assert.equal(forked.getLeafId(), checkpointEntryId);
			assert.deepEqual(forked.buildSessionContext().messages, seed.buildSessionContext().messages);
			const header: unknown = JSON.parse(
				readFileSync(fork.sessionFile, "utf8").split("\n")[0] ?? "",
			);
			assert.equal(
				header !== null && typeof header === "object"
					? Reflect.get(header, "parentSession")
					: undefined,
				seedSessionFile,
			);
		}

		const firstFork = SessionManager.open(forks[0]?.sessionFile ?? "", sessionDir);
		firstFork.appendMessage({
			role: "user",
			content: [{ type: "text", text: "Review practice A" }],
			timestamp: Date.now(),
		});
		assert.equal(
			SessionManager.open(forks[1]?.sessionFile ?? "", sessionDir).getLeafId(),
			checkpointEntryId,
		);
		assert.equal(SessionManager.open(seedSessionFile, sessionDir).getLeafId(), checkpointEntryId);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a checkpoint the file does not hold falls back to the entry it does", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-session-tree-"));
	try {
		const sessionDir = join(root, "sessions");
		const seed = SessionManager.create(root, sessionDir);
		seed.appendMessage({
			role: "user",
			content: [{ type: "text", text: "Gather group evidence" }],
			timestamp: Date.now(),
		});
		const persisted = seed.appendMessage(assistantMessage("Evidence manifest"));
		const seedSessionFile = seed.getSessionFile();
		assert.ok(seedSessionFile);

		const forks = forkSessions({
			seedSessionFile,
			// An entry id the live session reported and whose write never reached the file.
			checkpointEntryId: "0badf00d",
			keys: ["group-a"],
			sessionDir,
		});

		const forked = SessionManager.open(forks[0]?.sessionFile ?? "", sessionDir);
		assert.equal(forked.getLeafId(), persisted);
		assert.deepEqual(forked.buildSessionContext().messages, seed.buildSessionContext().messages);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("rejects duplicate and empty keys before creating a fork", () => {
	assert.throws(
		() =>
			forkSessions({
				seedSessionFile: "unused",
				checkpointEntryId: "unused",
				keys: ["group-a", "group-a"],
			}),
		/unique/,
	);
	assert.throws(
		() =>
			forkSessions({
				seedSessionFile: "unused",
				checkpointEntryId: "unused",
				keys: [""],
			}),
		/non-empty/,
	);
});
