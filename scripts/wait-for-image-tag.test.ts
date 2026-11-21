import assert from "node:assert/strict";
import { test } from "node:test";

import {
	AliasChangedError,
	currentDigest,
	waitForTag,
	waits,
	type TagAnswer,
} from "./wait-for-image-tag.ts";

const promoted = `sha256:${"a".repeat(64)}`;
const previous = `sha256:${"b".repeat(64)}`;
const foreign = `sha256:${"c".repeat(64)}`;
const reference = "ghcr.io/hephaestus-build/server:1.0";

/** A registry that gives these answers in turn, repeating the last, and records the pauses and log lines. */
function registry(answers: readonly TagAnswer[]) {
	const paused: number[] = [];
	const logged: string[] = [];
	let reads = 0;
	return {
		paused,
		logged,
		read: (): TagAnswer => {
			const answer = answers[Math.min(reads, answers.length - 1)];
			reads += 1;
			return answer ?? { notFound: true };
		},
		pause: async (seconds: number) => {
			paused.push(seconds);
		},
		log: (line: string) => {
			logged.push(line);
		},
	};
}

/** The error that `run` throws, or `undefined`. */
async function failure(run: () => Promise<unknown>): Promise<unknown> {
	try {
		await run();
		return undefined;
	} catch (error) {
		return error;
	}
}

void test("the waits double from 1 s up to 15 s and stop at 60 s", () => {
	assert.deepEqual(waits(), [1, 2, 4, 8, 15, 15, 15]);
});

void test("a tag that already resolves to the promoted digest passes without a wait", async () => {
	const fake = registry([{ digest: promoted }]);
	await waitForTag(reference, promoted, previous, fake);
	assert.deepEqual(fake.paused, []);
});

void test("a tag that answers its previous digest or no tag is read again until it serves the promoted one", async () => {
	const fake = registry([{ digest: previous }, { notFound: true }, { digest: promoted }]);
	await waitForTag(reference, promoted, previous, fake);
	assert.deepEqual(fake.paused, [1, 2]);
	assert.match(fake.logged[0] ?? "", /still sha256:b+, not the promoted sha256:a+, 0 s after/u);
	assert.match(fake.logged[1] ?? "", /no such tag, not the promoted .*; reading it again in 2 s/u);
});

void test("a tag that resolves to a third digest fails at once: another writer moved it", async () => {
	for (const before of [previous, null]) {
		const fake = registry([{ digest: foreign }]);
		const error = await failure(async () => waitForTag(reference, promoted, before, fake));
		assert.ok(error instanceof AliasChangedError);
		assert.match(error.message, /resolves to sha256:c+, which is neither its digest before/u);
		assert.deepEqual(fake.paused, []);
	}
});

void test("a lag that outlasts 60 s fails with what the tag served last", async () => {
	const fake = registry([{ digest: previous }]);
	const error = await failure(async () => waitForTag(reference, promoted, previous, fake));
	assert.ok(error instanceof Error && !(error instanceof AliasChangedError));
	assert.deepEqual(fake.paused, [1, 2, 4, 8, 15, 15, 15]);
	assert.match(error.message, /still sha256:b+, not the promoted sha256:a+, 60 s after/u);
});

void test("a rate limit or a server error is read again within the same 60 s; another error fails at once", async () => {
	const transient = registry([
		{ transient: "429 Too Many Requests" },
		{ transient: "502 Bad Gateway" },
		{ digest: promoted },
	]);
	await waitForTag(reference, promoted, previous, transient);
	assert.deepEqual(transient.paused, [1, 2]);
	assert.match(transient.logged[0] ?? "", /429 Too Many Requests, 0 s after the first read/u);

	const denied = registry([{ error: "unauthorized: authentication required" }]);
	const error = await failure(async () => waitForTag(reference, promoted, previous, denied));
	assert.ok(error instanceof Error);
	assert.equal(error.message, `Cannot read ${reference}: unauthorized: authentication required`);
	assert.deepEqual(denied.paused, []);
});

void test("the digest before the promotion is the tag's digest, or none, read through transient errors", async () => {
	assert.equal(await currentDigest(reference, registry([{ digest: previous }])), previous);
	assert.equal(await currentDigest(reference, registry([{ notFound: true }])), null);
	const transient = registry([{ transient: "503 Service Unavailable" }, { digest: previous }]);
	assert.equal(await currentDigest(reference, transient), previous);
	assert.deepEqual(transient.paused, [1]);
	const error = await failure(async () =>
		currentDigest(reference, registry([{ error: "denied" }])),
	);
	assert.ok(error instanceof Error && error.message === `Cannot read ${reference}: denied`);
});
