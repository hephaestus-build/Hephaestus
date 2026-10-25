import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	chartVersion,
	jointLockAsset,
	jointLockFromEvidence,
	legacyLock,
	lockEnvironment,
	parseReleaseImageLock,
	verifyLegacyProjection,
	verifyLockAgainstEvidence,
} from "./release-image-lock.ts";

/**
 * scripts/release-image-lock.ts as tagged v0.94.7 (commit 97bdc0dd532a0cbe0fe761c166cb6a7f03964b09,
 * blob 1b73a820414bed90435dab60f65779ac87cb0254), byte for byte. A host one release behind verifies
 * a new release with this reader before it adopts any new tooling. It is a baseline, never a helper.
 */
const previousReader = path.join(
	import.meta.dirname,
	"fixtures/release-v0.94.7/release-image-lock.ts",
);
const previousReaderSha256 = "f8b58a8345bd9c93a57d909c148ce529e4223bbf6b8bafa082915c95fe91802c";

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
	assert.match(environment, /^IMAGE_TAG=1\.2\.3$/mu);
	assert.match(environment, /HEPHAESTUS_IMAGE_WEBAPP=.+@sha256:[a-f0-9]{64}/u);
});

await test("refuses malformed and ambiguous locks", () => {
	assert.throws(() => parseReleaseImageLock({ ...rawLock, extra: true }), /missing or extra/u);
	for (const release of ["1.2.3", "v01.2.3", "v1.2.3-01", "v1.2.3-.."]) {
		assert.throws(() => parseReleaseImageLock({ ...rawLock, release }), /malformed release/u);
	}
	assert.throws(() => parseReleaseImageLock(rawLock, "v1.2.4"), /not v1.2.4/u);
	assert.throws(
		() => parseReleaseImageLock({ ...rawLock, images: [rawLock.images[0], rawLock.images[0]] }),
		/duplicate image/u,
	);
	assert.throws(
		() =>
			parseReleaseImageLock({
				...rawLock,
				images: [{ ...rawLock.images[0], indexDigest: "sha256:bad" }],
			}),
		/malformed index digest/u,
	);
	assert.throws(
		() =>
			parseReleaseImageLock({
				...rawLock,
				images: [{ ...rawLock.images[0], platforms: { "linux/amd64": digest("c") } }],
			}),
		/missing or extra fields/u,
	);
});

const chart = {
	repository: "ghcr.io/hephaestus-build/charts/hephaestus",
	version: "1.2.3",
	digest: digest("e"),
};
const jointLock = { ...rawLock, schemaVersion: 2, chart };

await test("reads a joint lock with the same images, source and environment as schema 1", () => {
	const lock = parseReleaseImageLock(jointLock, "v1.2.3");
	// assert.equal narrows the lock to schema 2, so its chart is read with its own type.
	assert.equal(lock.schemaVersion, 2);
	assert.deepEqual(lock.chart, chart);
	verifyLockAgainstEvidence(lock, evidence);
	assert.equal(lockEnvironment(lock), lockEnvironment(parseReleaseImageLock(rawLock, "v1.2.3")));
	assert.equal(chartVersion("v1.2.3-rc.1"), "1.2.3-rc.1");
});

await test("keeps historical schema 1 strict: a chart or an unknown schema is refused", () => {
	assert.equal(parseReleaseImageLock(rawLock, "v1.2.3").schemaVersion, 1);
	assert.throws(() => parseReleaseImageLock({ ...rawLock, chart }), /missing or extra/u);
	for (const schemaVersion of [0, 3, "2", undefined]) {
		assert.throws(
			() => parseReleaseImageLock({ ...jointLock, schemaVersion }),
			/unsupported lock schema/u,
		);
	}
});

await test("refuses a joint lock whose chart is missing, extended or not this release's", () => {
	const { chart: _chart, ...withoutChart } = jointLock;
	assert.throws(() => parseReleaseImageLock(withoutChart), /missing or extra/u);
	assert.throws(
		() => parseReleaseImageLock({ ...jointLock, chart: { ...chart, tag: "1.2.3" } }),
		/chart has missing or extra fields/u,
	);
	for (const repository of [
		"ghcr.io/hephaestus-build/hephaestus",
		"ghcr.io/hephaestus-build/charts/other",
		"docker.io/hephaestus-build/charts/hephaestus",
		"ghcr.io/hephaestus-build/charts/hephaestus:1.2.3",
	]) {
		assert.throws(
			() => parseReleaseImageLock({ ...jointLock, chart: { ...chart, repository } }),
			/chart has a malformed repository/u,
			repository,
		);
	}
	for (const version of ["v1.2.3", "1.2.4", "latest", 123]) {
		assert.throws(
			() => parseReleaseImageLock({ ...jointLock, chart: { ...chart, version } }),
			/chart version is not 1\.2\.3/u,
			String(version),
		);
	}
	for (const chartDigest of ["sha256:short", "latest", digest("E")]) {
		assert.throws(
			() => parseReleaseImageLock({ ...jointLock, chart: { ...chart, digest: chartDigest } }),
			/chart has a malformed digest/u,
		);
	}
	// The release and source identity stay one fact for the images and the chart.
	assert.throws(() => parseReleaseImageLock(jointLock, "v1.2.4"), /not v1.2.4/u);
	assert.throws(
		() => parseReleaseImageLock({ ...jointLock, commit: "main" }),
		/malformed source commit/u,
	);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(parseReleaseImageLock(jointLock), {
				...evidence,
				commit: "f".repeat(40),
			}),
		/does not match the evidence manifest/u,
	);
});

await test("refuses evidence that is not exactly represented by the lock", () => {
	const lock = parseReleaseImageLock(rawLock);
	assert.throws(() => verifyLockAgainstEvidence(lock, { ...evidence, subjects: [] }), /missing/u);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [...evidence.subjects, evidence.subjects[0]],
			}),
		/duplicate evidence subject/u,
	);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [...evidence.subjects, { ...evidence.subjects[0], image: "rogue" }],
			}),
		/unlocked image/u,
	);
	assert.throws(
		() =>
			verifyLockAgainstEvidence(lock, {
				...evidence,
				subjects: [{ ...evidence.subjects[0], digest: digest("e") }, evidence.subjects[1]],
			}),
		/disagree/u,
	);
});

await test("one producer writes the joint lock, and the schema 1 lock is exactly its projection", () => {
	const joint = jointLockFromEvidence(evidence, chart, "v1.2.3");
	assert.deepEqual(joint, parseReleaseImageLock(jointLock, "v1.2.3"));
	const legacy = legacyLock(joint);
	assert.deepEqual(legacy, parseReleaseImageLock(rawLock, "v1.2.3"));
	verifyLegacyProjection(joint, parseReleaseImageLock(structuredClone(legacy), "v1.2.3"));
	assert.equal(lockEnvironment(legacy), lockEnvironment(joint));
	assert.equal(jointLockAsset("v1.2.3"), "release-v1.2.3.chart.json");
	// A schema 1 lock that names other images, another commit, or a joint lock is refused.
	for (const other of [
		{ ...rawLock, commit: "f".repeat(40) },
		{ ...rawLock, images: [{ ...rawLock.images[0], indexDigest: digest("9") }] },
	]) {
		assert.throws(
			() => verifyLegacyProjection(joint, parseReleaseImageLock(other, "v1.2.3")),
			/not the projection of the joint lock/u,
		);
	}
	assert.throws(() => verifyLegacyProjection(joint, joint), /not the projection/u);
	assert.throws(() => verifyLegacyProjection(legacy, legacy), /not the projection/u);
});

await test("the producer refuses evidence it cannot turn into one strict joint lock", () => {
	assert.throws(
		() => jointLockFromEvidence(evidence, chart, "v1.2.4"),
		/does not describe v1\.2\.4/u,
	);
	assert.throws(
		() => jointLockFromEvidence(evidence, { ...chart, version: "1.2.4" }, "v1.2.3"),
		/chart version is not 1\.2\.3/u,
	);
	assert.throws(
		() => jointLockFromEvidence(evidence, {}, "v1.2.3"),
		/chart has missing or extra fields/u,
	);
	const [amd64, arm64] = evidence.subjects;
	for (const [subjects, message] of [
		[[amd64], /missing or extra fields/u],
		[[amd64, arm64, amd64], /duplicate evidence subject|missing or extra/u],
		[[amd64, { ...arm64, indexDigest: digest("9") }], /disagrees about webapp/u],
		[
			[amd64, { ...arm64, repository: "ghcr.io/hephaestus-build/other" }],
			/disagrees about webapp/u,
		],
		[[{ ...amd64, image: 7 }, arm64], /no image name/u],
	] as const) {
		assert.throws(() => jointLockFromEvidence({ ...evidence, subjects }, chart, "v1.2.3"), message);
	}
	assert.throws(
		() => jointLockFromEvidence({ ...evidence, commit: "main" }, chart, "v1.2.3"),
		/malformed source commit/u,
	);
	assert.throws(
		() => jointLockFromEvidence({ ...evidence, schemaVersion: 2 }, chart, "v1.2.3"),
		/does not match the evidence manifest/u,
	);
});

await test("the v0.94.7 reader accepts the produced schema 1 lock and refuses the joint lock", async (t) => {
	// The old parser and its CLI only: this is not a run of the old reconciler or of a Docker host.
	assert.equal(
		createHash("sha256")
			.update(await readFile(previousReader))
			.digest("hex"),
		previousReaderSha256,
		"the archived reader must stay byte-exact",
	);
	const directory = await mkdtemp(path.join(tmpdir(), "previous-reader-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const joint = jointLockFromEvidence(evidence, chart, "v1.2.3");
	const files = {
		manifest: path.join(directory, "manifest.json"),
		legacy: path.join(directory, "release-v1.2.3.json"),
		joint: path.join(directory, jointLockAsset("v1.2.3")),
	};
	await writeFile(files.manifest, JSON.stringify(evidence));
	await writeFile(files.legacy, JSON.stringify(legacyLock(joint)));
	await writeFile(files.joint, JSON.stringify(joint));
	const previous = (lock: string, output: string) =>
		spawnSync(
			process.execPath,
			[previousReader, lock, files.manifest, "v1.2.3", path.join(directory, output)],
			{
				encoding: "utf8",
				timeout: 30_000,
			},
		);
	const accepted = previous(files.legacy, "previous.env");
	assert.equal(accepted.status, 0, accepted.stderr);
	assert.equal(
		await readFile(path.join(directory, "previous.env"), "utf8"),
		lockEnvironment(joint),
	);
	const refused = previous(files.joint, "joint.env");
	assert.notEqual(refused.status, 0);
	assert.match(refused.stderr, /lock has missing or extra fields/u);
});
