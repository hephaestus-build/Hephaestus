import assert from "node:assert/strict";
import { test } from "node:test";

import { waitForTag, waits, type TagAnswer } from "./wait-for-image-tag.ts";

const promoted = `sha256:${"a".repeat(64)}`;
const previous = `sha256:${"b".repeat(64)}`;
const reference = "ghcr.io/hephaestus-build/server:1.0";

/** Runs the wait against a registry that gives these answers in turn, and records the pauses and log lines. */
async function run(
	answers: TagAnswer[],
): Promise<{ paused: number[]; logged: string[]; error?: string }> {
	const paused: number[] = [];
	const logged: string[] = [];
	let reads = 0;
	try {
		await waitForTag(
			reference,
			promoted,
			() => {
				const answer = answers[Math.min(reads, answers.length - 1)];
				reads += 1;
				return answer ?? { notFound: true };
			},
			async (seconds) => {
				paused.push(seconds);
			},
			(line) => {
				logged.push(line);
			},
		);
		return { paused, logged };
	} catch (error) {
		return { paused, logged, error: error instanceof Error ? error.message : String(error) };
	}
}

void test("the waits double from 1 s up to 15 s and stop at 60 s", () => {
	assert.deepEqual(waits(), [1, 2, 4, 8, 15, 15, 15]);
});

void test("a tag that already resolves to the promoted digest passes without a wait", async () => {
	assert.deepEqual(await run([{ digest: promoted }]), { paused: [], logged: [] });
});

void test("a tag that still serves its old digest, or no digest, is read again until it serves the promoted one", async () => {
	const result = await run([{ digest: previous }, { notFound: true }, { digest: promoted }]);
	assert.equal(result.error, undefined);
	assert.deepEqual(result.paused, [1, 2]);
	assert.match(
		result.logged[0] ?? "",
		new RegExp(`resolves to ${previous}, not ${promoted}, 0 s after`, "u"),
	);
	assert.match(
		result.logged[1] ?? "",
		/resolves to no such tag, .*, 1 s after the promotion; reading it again in 2 s/u,
	);
});

void test("a tag that never serves the promoted digest fails at 60 s and names what it served", async () => {
	const result = await run([{ digest: previous }]);
	assert.deepEqual(result.paused, [1, 2, 4, 8, 15, 15, 15]);
	assert.equal(
		result.error,
		`${reference} resolves to ${previous}, not ${promoted}, 60 s after the promotion`,
	);
});

void test("another registry error fails at once, without a wait", async () => {
	const result = await run([{ error: "unauthorized: authentication required" }]);
	assert.deepEqual(result.paused, []);
	assert.equal(result.error, `Cannot read ${reference}: unauthorized: authentication required`);
});
