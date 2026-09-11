import assert from "node:assert/strict";
import { test } from "node:test";
import {
	lockEnvironment,
	parseReleaseImageLock,
	verifyLockAgainstEvidence,
} from "./release-image-lock.ts";

const digest = (character: string) => `sha256:${character.repeat(64)}`;
const rawLock = {
	schemaVersion: 1,
	release: "v1.2.3",
	commit: "a".repeat(40),
	images: [
		{
			image: "webapp",
			repository: "ghcr.io/hephaestus-build/webapp",
			provenance: "first-party",
			indexDigest: digest("b"),
			platforms: { "linux/amd64": digest("c"), "linux/arm64": digest("d") },
		},
	],
} as const;

const evidence = {
	schemaVersion: 1,
	release: rawLock.release,
	commit: rawLock.commit,
	subjects: [
		{ ...rawLock.images[0], platform: "linux/amd64", digest: digest("c"), platforms: undefined },
		{ ...rawLock.images[0], platform: "linux/arm64", digest: digest("d"), platforms: undefined },
	].map(({ platforms: _platforms, ...subject }) => subject),
};

await test("accepts a complete lock and emits only digest references", () => {
	const lock = parseReleaseImageLock(rawLock, "v1.2.3");
	verifyLockAgainstEvidence(lock, evidence);
	const environment = lockEnvironment(lock);
	assert.match(environment, /^IMAGE_TAG=1\.2\.3$/m);
	assert.match(environment, /HEPHAESTUS_IMAGE_WEBAPP=.+@sha256:[a-f0-9]{64}/);
});

await test("refuses malformed and ambiguous locks", () => {
	assert.throws(() => parseReleaseImageLock({ ...rawLock, extra: true }), /missing or extra/);
	for (const release of ["1.2.3", "v01.2.3", "v1.2.3-01", "v1.2.3-.."])
		assert.throws(() => parseReleaseImageLock({ ...rawLock, release }), /malformed release/);
	assert.throws(() => parseReleaseImageLock(rawLock, "v1.2.4"), /not v1.2.4/);
	assert.throws(
		() => parseReleaseImageLock({ ...rawLock, images: [rawLock.images[0], rawLock.images[0]] }),
		/duplicate image/,
	);
	assert.throws(
		() =>
			parseReleaseImageLock({
				...rawLock,
				images: [{ ...rawLock.images[0], indexDigest: "sha256:bad" }],
			}),
		/malformed index digest/,
	);
	assert.throws(
		() =>
			parseReleaseImageLock({
				...rawLock,
				images: [{ ...rawLock.images[0], platforms: { "linux/amd64": digest("c") } }],
			}),
		/missing or extra fields/,
	);
});

await test("refuses evidence that is not exactly represented by the lock", () => {
	const lock = parseReleaseImageLock(rawLock);
	assert.throws(() => verifyLockAgainstEvidence(lock, { ...evidence, subjects: [] }), /missing/);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [...evidence.subjects, evidence.subjects[0]],
			}),
		/duplicate evidence subject/,
	);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [...evidence.subjects, { ...evidence.subjects[0], image: "rogue" }],
			}),
		/unlocked image/,
	);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [{ ...evidence.subjects[0], digest: digest("e") }, evidence.subjects[1]],
			}),
		/disagree/,
	);
});
