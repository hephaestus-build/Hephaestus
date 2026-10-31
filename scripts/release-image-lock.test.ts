import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	chartVersion,
	jointLockAsset,
	jointLockFromEvidence,
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

await test("accepts a complete lock that matches the evidence", () => {
	const lock = parseReleaseImageLock(rawLock, "v1.2.3");
	verifyLockAgainstEvidence(lock, evidence);
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

await test("reads the joint lock with its chart, images and source", () => {
	const lock = parseReleaseImageLock(jointLock, "v1.2.3");
	// assert.equal narrows the lock to schema 2, so its chart is read with its own type.
	assert.equal(lock.schemaVersion, 2);
	assert.deepEqual(lock.chart, chart);
	verifyLockAgainstEvidence(lock, evidence);
	assert.deepEqual(lock.images, rawLock.images);
	assert.equal(lock.commit, rawLock.commit);
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

await test("the producer writes one joint lock from release evidence and chart metadata", () => {
	const joint = jointLockFromEvidence(evidence, chart, "v1.2.3");
	assert.deepEqual(joint, parseReleaseImageLock(jointLock, "v1.2.3"));
	assert.equal(jointLockAsset("v1.2.3"), "release-v1.2.3.chart.json");
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

await test("the CLI writes and verifies one joint lock and refuses legacy commands", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "release-lock-cli-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const manifest = path.join(directory, "manifest.json");
	const metadata = path.join(directory, "chart.json");
	const output = path.join(directory, jointLockAsset("v1.2.3"));
	const legacy = path.join(directory, "release-v1.2.3.json");
	const environment = path.join(directory, "release-lock.env");
	await writeFile(manifest, JSON.stringify(evidence));
	await writeFile(metadata, JSON.stringify(chart));
	const execute = (...args: string[]) =>
		spawnSync(
			process.execPath,
			[new URL("release-image-lock.ts", import.meta.url).pathname, ...args],
			{
				encoding: "utf8",
				timeout: 30_000,
			},
		);
	const produced = execute("write", manifest, metadata, "v1.2.3", output);
	assert.equal(produced.status, 0, produced.stderr);
	const original = await readFile(output, "utf8");
	assert.deepEqual(JSON.parse(original), jointLock);
	assert.deepEqual(
		(await readdir(directory)).sort(),
		["chart.json", "manifest.json", path.basename(output)].sort(),
	);
	const verified = execute("verify", output, manifest, "v1.2.3");
	assert.equal(verified.status, 0, verified.stderr);
	assert.notEqual(execute("write", manifest, metadata, "v1.2.3", output).status, 0);
	assert.equal(await readFile(output, "utf8"), original);
	for (const args of [
		["write", manifest, metadata, "v1.2.3", output, legacy],
		["projection", output, legacy, "v1.2.3"],
		[output, manifest, "v1.2.3", environment],
	]) {
		const refused = execute(...args);
		assert.notEqual(refused.status, 0);
		assert.match(refused.stderr, /usage: release-image-lock/u);
	}
	await assert.rejects(readFile(legacy), { code: "ENOENT" });
	await assert.rejects(readFile(environment), { code: "ENOENT" });
	await writeFile(output, JSON.stringify(rawLock));
	const refusedSchema = execute("verify", output, manifest, "v1.2.3");
	assert.notEqual(refusedSchema.status, 0);
	assert.match(refusedSchema.stderr, /expected a joint lock/u);
	await writeFile(output, original);
	await writeFile(manifest, JSON.stringify({ ...evidence, commit: "f".repeat(40) }));
	const refusedEvidence = execute("verify", output, manifest, "v1.2.3");
	assert.notEqual(refusedEvidence.status, 0);
	assert.match(refusedEvidence.stderr, /does not match the evidence manifest/u);
});
