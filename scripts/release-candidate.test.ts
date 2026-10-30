import assert from "node:assert/strict";
import { test } from "node:test";

import { parseJson } from "./lib/json.ts";

import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { compareVersions, imageAliases, parseVersion } from "./lib/release-version.ts";
import {
	assertCurrentStableRelease,
	assertPromotionCandidate,
	planCandidatePromotion,
	planRelease,
	releaseOutputs,
	renderReleaseNotes,
	type ReleaseRef,
} from "./plan-release.ts";
import { assertCandidateArtifacts } from "./promote-release-candidate.ts";
import { parseChannel, serializeChannel, decide } from "./reconcile-deployment.ts";
import { parseReleaseImageLock } from "./release-image-lock.ts";
import { resolvePromotion } from "./resolve-promotion.ts";

const sha = "a".repeat(40);
const stable: ReleaseRef = {
	tag: "v0.95.1",
	isDraft: false,
	isPrerelease: false,
	targetCommitish: "main",
};
const candidate: ReleaseRef = {
	tag: "v1.0.0-rc.2",
	isDraft: false,
	isPrerelease: true,
	isImmutable: true,
	targetCommitish: sha,
};

await test("numbered candidates sort numerically before stable and never select stable aliases", () => {
	const versions = ["0.95.1", "1.0.0-rc.1", "1.0.0-rc.2", "1.0.0-rc.10", "1.0.0"];
	for (const [index, version] of versions.entries()) {
		const parsed = parseVersion(version);
		assert.ok(parsed);
		for (const [otherIndex, other] of versions.entries()) {
			const compared = parseVersion(other);
			assert.ok(compared);
			assert.equal(Math.sign(compareVersions(parsed, compared)), Math.sign(index - otherIndex));
		}
	}
	for (const invalid of [
		"v1.0.0",
		"01.0.0",
		"1.0.0-rc.01",
		"1.0.0-beta.1",
		"1.0.0-rc",
		"1.0.0-rc.1+build",
		"1.0.0-rc.1\n",
		"999999999999999999.0.0",
	]) {
		assert.equal(parseVersion(invalid), undefined, invalid);
	}
	assert.deepEqual(imageAliases("1.0.0-rc.2"), ["1.0.0-rc.2"]);
	assert.deepEqual(imageAliases("1.0.0"), ["1.0.0", "1.0", "latest"]);
});

await test("RC plans retain the stable upgrade seed and expose prerelease publication flags", () => {
	const plan = planRelease(sha, "1.0.0-rc.2", [stable], false);
	assert.equal(plan.kind, "cut");
	const outputs = releaseOutputs(plan);
	assert.equal(outputs.prerelease, "true");
	assert.equal(outputs.previous_version, "0.95.1");
	assert.equal(outputs.notes_version, "1.0.0-rc.2");
	assert.doesNotThrow(() => assertCurrentStableRelease("1.0.0-rc.2", [stable, candidate]));
	for (const change of [{ isDraft: true }, { isPrerelease: false }, { isImmutable: false }]) {
		assert.throws(
			() => assertCurrentStableRelease("1.0.0-rc.2", [{ ...candidate, ...change }]),
			/immutable candidate/u,
		);
	}
});

await test("stable must explicitly promote the latest immutable candidate at its exact source", () => {
	const releases = [stable, candidate];
	assert.equal(planRelease(sha, "1.0.0", releases, false).kind, "refuse");
	const promoted = planCandidatePromotion(sha, candidate.tag, releases, false);
	assert.equal(promoted.kind, "cut");
	assert.equal(releaseOutputs(promoted).prerelease, "false");
	assert.equal(releaseOutputs(promoted).candidate, candidate.tag);
	assert.equal(releaseOutputs(promoted).notes_version, "1.0.0-rc.2");
	assert.equal(releaseOutputs(promoted).tag_name, "v1.0.0");
	assert.equal(planCandidatePromotion(sha, candidate.tag, releases, true).kind, "refuse");
	for (const change of [
		{ isDraft: true },
		{ isPrerelease: false },
		{ isImmutable: false },
		{ targetCommitish: "b".repeat(40) },
	]) {
		assert.throws(() =>
			assertPromotionCandidate(sha, candidate.tag, [stable, { ...candidate, ...change }]),
		);
	}
	for (const bad of ["v1.0.0", "1.0.0-rc.2", "v1.0.0-rc.02"]) {
		assert.throws(() => assertPromotionCandidate(sha, bad, releases));
	}
	assert.throws(() => assertPromotionCandidate("main", candidate.tag, releases));
	assert.throws(() => assertPromotionCandidate(sha, candidate.tag, [stable]));
	for (const isDraft of [false, true]) {
		assert.throws(
			() =>
				assertPromotionCandidate(sha, candidate.tag, [
					...releases,
					{ ...candidate, tag: "v1.0.0-rc.10", isDraft },
				]),
			/newer candidate/u,
		);
	}
});

await test("promotion preserves every image index, platform and source while chart metadata changes", () => {
	const source = {
		schemaVersion: 2,
		release: candidate.tag,
		commit: sha,
		chart: {
			repository: "ghcr.io/hephaestus-build/charts/hephaestus",
			version: "1.0.0-rc.2",
			digest: `sha256:${"b".repeat(64)}`,
		},
		images: [
			{
				image: "webapp",
				repository: "ghcr.io/hephaestus-build/webapp",
				provenance: "first-party",
				indexDigest: `sha256:${"c".repeat(64)}`,
				platforms: {
					"linux/amd64": `sha256:${"d".repeat(64)}`,
					"linux/arm64": `sha256:${"e".repeat(64)}`,
				},
			},
		],
	};
	const stableLock = {
		...source,
		release: "v1.0.0",
		chart: { ...source.chart, version: "1.0.0", digest: `sha256:${"f".repeat(64)}` },
	};
	assertCandidateArtifacts(parseReleaseImageLock(source), parseReleaseImageLock(stableLock));
	for (const changed of [
		{ ...stableLock, commit: "b".repeat(40) },
		{
			...stableLock,
			chart: { ...stableLock.chart, repository: "ghcr.io/another/charts/hephaestus" },
		},
		{ ...stableLock, images: [{ ...source.images[0], indexDigest: `sha256:${"f".repeat(64)}` }] },
		{
			...stableLock,
			images: [
				{
					...source.images[0],
					platforms: { ...source.images[0]?.platforms, "linux/arm64": `sha256:${"f".repeat(64)}` },
				},
			],
		},
	]) {
		assert.throws(
			() => assertCandidateArtifacts(parseReleaseImageLock(source), parseReleaseImageLock(changed)),
			/Promotion changed/u,
		);
	}
	for (const version of [
		"1.0.0-rc.2",
		"1.0.1-rc.2",
		"v1.0.0-rc.2",
		"1.0.0-rc.02",
		"1.0.0-beta.2",
	]) {
		assert.throws(
			() => parseReleaseImageLock({ ...stableLock, chart: { ...source.chart, version } }),
			/chart version/u,
		);
	}
});

await test("candidate notes and stopped upgrades keep the full 1.0 cutover instructions", async () => {
	const changelog =
		"# hephaestus\n\n## 1.0.0-rc.2\n\nFix the candidate.\n\n## 1.0.0-rc.1\n\nRemove Compose.\n\n## 0.95.1\n\nStable.\n";
	const notes = await renderReleaseNotes(changelog, "0.95.1", "1.0.0-rc.2");
	assert.match(notes, /Fix the candidate[\s\S]*Remove Compose/u);
	assert.doesNotMatch(notes, /Stable\./u);
	const guide =
		"### Next release\n\n### v1.0.0-rc.2\n\n#### 🔴 Stop every role for candidate correction\n\n### v1.0.0-rc.1\n\n#### 🔴 Stop every role for full cutover\n\n### v0.95.1\n\n";
	assert.equal(stopEveryRoleEntries(guide, [], "0.95.1").length, 2);
	assert.deepEqual(stopEveryRoleEntries(guide, [], "1.0.0-rc.1"), [
		"#### 🔴 Stop every role for candidate correction",
	]);
});

await test("release channels accept numbered candidates and refuse candidate downgrades", async () => {
	const promoted = await resolvePromotion(
		{
			environment: "Staging",
			release: "v1.0.0-rc.2",
			allowRollback: false,
			freeze: true,
			refreshDatabaseImage: false,
		},
		{ compare: async () => "identical", isDraft: async () => false, images: async () => ({}) },
	);
	assert.equal(parseChannel(parseJson(serializeChannel(promoted.channel))).release, "v1.0.0-rc.2");
	const state = {
		release: "v1.0.0-rc.10",
		commit: sha,
		channelCommit: "b".repeat(40),
		appliedAt: "2026-10-30T12:00:00Z",
	};
	assert.equal(
		decide(promoted.channel, state, "c".repeat(40), "d".repeat(64), true).action,
		"refuse",
	);
	assert.equal(
		decide({ release: "v1.0.0" }, state, "c".repeat(40), "d".repeat(64), true).action,
		"apply",
	);
	assert.equal(
		decide(promoted.channel, { ...state, release: "v1.0.0" }, "c".repeat(40), "d".repeat(64), true)
			.action,
		"refuse",
	);
	assert.equal(
		decide(
			{ ...promoted.channel, allowRollback: true },
			state,
			"c".repeat(40),
			"d".repeat(64),
			true,
		).action,
		"apply",
	);
});
