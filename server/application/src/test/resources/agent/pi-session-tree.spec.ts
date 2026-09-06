import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { SessionManager } from "@earendil-works/pi-coding-agent";

import { forkSessions, reconnaissanceSeed } from "../../../main/resources/agent/pi-session-tree.ts";

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

void test("a reconnaissance that ran out of budget is not a seed", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-session-tree-"));
	try {
		const sessionDir = join(root, "sessions");
		const recon = SessionManager.create(root, sessionDir);
		recon.appendMessage({
			role: "user",
			content: [{ type: "text", text: "Build one factual reconnaissance map" }],
			timestamp: Date.now(),
		});
		// A disposed session reports this leaf either way; only the deadline knows the write behind it
		// never landed.
		const checkpointEntryId = recon.appendMessage(assistantMessage("Reconnaissance map"));
		const seedSessionFile = recon.getSessionFile();
		assert.ok(seedSessionFile);

		assert.throws(
			() => reconnaissanceSeed(recon, { expired: true }, 120_000),
			/did not answer within 120s/,
		);
		assert.deepEqual(reconnaissanceSeed(recon, { expired: false }, 120_000), {
			seedSessionFile,
			checkpointEntryId,
		});
		assert.throws(
			() =>
				reconnaissanceSeed(SessionManager.create(root, sessionDir), { expired: false }, 120_000),
			/no persistent checkpoint/,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a review with nothing to fork gets no forks and no seed is opened", () => {
	assert.deepEqual(
		forkSessions({ seedSessionFile: "unused", checkpointEntryId: "unused", keys: [] }),
		[],
	);
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
