import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	currentReleaseIdentity,
	loadReleaseIdentities,
	parseReleaseIdentities,
	releaseCertificateIdentity,
	releaseIdentityFor,
	releaseOwner,
	releaseRepository,
} from "./lib/release-identities.ts";

const identities = loadReleaseIdentities();

await test("the committed identity map pins the pre-transfer namespace and identity", () => {
	const first = identities[0];
	assert.deepEqual(first, {
		firstVersion: "0.0.0",
		namespace: "ghcr.io/ls1intum/hephaestus",
		certificateIdentityRepository: "ls1intum/Hephaestus",
	});
	assert.deepEqual(currentReleaseIdentity(identities), {
		firstVersion: identities.at(-1)?.firstVersion,
		namespace: "ghcr.io/hephaestus-build",
		certificateIdentityRepository: "hephaestus-build/Hephaestus",
	});
});

await test("versions resolve to the identity they were published under", () => {
	const boundary = currentReleaseIdentity(identities).firstVersion;
	for (const old of ["0.1.0", "v0.74.0", "0.74.99"]) {
		assert.equal(releaseIdentityFor(old, identities).namespace, "ghcr.io/ls1intum/hephaestus");
	}
	for (const current of [boundary, `v${boundary}`, "1.0.0", "12.0.3"]) {
		assert.equal(releaseIdentityFor(current, identities).namespace, "ghcr.io/hephaestus-build");
	}
	assert.throws(() => releaseIdentityFor("latest", identities), /not a release version/u);
	assert.throws(() => releaseIdentityFor("v1.2", identities), /not a release version/u);
});

await test("historical certificate identities stay pinned even inside CI", () => {
	assert.equal(
		releaseCertificateIdentity(
			"v0.74.0",
			{ GITHUB_SERVER_URL: "https://github.com", GITHUB_REPOSITORY: "hephaestus-build/Hephaestus" },
			identities,
		),
		"https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
});

await test("the current certificate identity follows the run context, with the map as fallback", () => {
	const boundary = currentReleaseIdentity(identities).firstVersion;
	assert.equal(
		releaseCertificateIdentity(
			`v${boundary}`,
			{ GITHUB_SERVER_URL: "https://github.com", GITHUB_REPOSITORY: "some-fork/Hephaestus" },
			identities,
		),
		"https://github.com/some-fork/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
	assert.equal(
		releaseCertificateIdentity(`v${boundary}`, {}, identities),
		"https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
});

function entry(firstVersion: string) {
	return {
		firstVersion,
		namespace: "ghcr.io/example",
		certificateIdentityRepository: "example/Example",
	};
}

await test("the map rejects malformed or unordered entries", () => {
	assert.throws(
		() => parseReleaseIdentities({ schemaVersion: 2, identities: [entry("0.0.0")] }),
		/schema version 1/u,
	);
	assert.throws(
		() => parseReleaseIdentities({ schemaVersion: 1, identities: [] }),
		/at least one entry/u,
	);
	assert.throws(
		() => parseReleaseIdentities({ schemaVersion: 1, identities: [entry("0.1.0")] }),
		/start at 0\.0\.0/u,
	);
	assert.throws(
		() =>
			parseReleaseIdentities({
				schemaVersion: 1,
				identities: [entry("0.0.0"), entry("2.0.0"), entry("1.0.0")],
			}),
		/strictly ascending/u,
	);
	assert.throws(
		() =>
			parseReleaseIdentities({
				schemaVersion: 1,
				identities: [{ ...entry("0.0.0"), namespace: "ghcr.io/example/" }],
			}),
		/trailing slash/u,
	);
	assert.throws(
		() =>
			parseReleaseIdentities({
				schemaVersion: 1,
				identities: [{ ...entry("0.0.0"), certificateIdentityRepository: "example" }],
			}),
		/owner\/name/u,
	);
});

await test("a misconfigured CI environment fails instead of using the map fallback", () => {
	const boundary = currentReleaseIdentity(identities).firstVersion;
	assert.equal(releaseRepository(`v${boundary}`, {}, identities), "hephaestus-build/Hephaestus");
	assert.throws(
		() => releaseRepository(`v${boundary}`, { CI: "true" }, identities),
		/GITHUB_REPOSITORY/u,
	);
	assert.throws(
		() => releaseCertificateIdentity(`v${boundary}`, { CI: "true" }, identities),
		/GITHUB_REPOSITORY/u,
	);
	assert.equal(releaseRepository("v0.74.0", { CI: "true" }, identities), "ls1intum/Hephaestus");
});

await test("image-index signatures resolve the building repository, owner and workflow", () => {
	const currentRun = { GITHUB_REPOSITORY: "hephaestus-build/Hephaestus" };
	assert.equal(releaseOwner("v0.74.0", currentRun, identities), "ls1intum");
	assert.equal(releaseOwner("v1.0.0", currentRun, identities), "hephaestus-build");
	assert.equal(
		releaseCertificateIdentity("v0.74.0", currentRun, identities, "reusable-docker-build.yml"),
		"https://github.com/ls1intum/Hephaestus/.github/workflows/reusable-docker-build.yml@refs/heads/main",
	);
	assert.equal(
		releaseCertificateIdentity("v0.74.0", currentRun, identities),
		"https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main",
	);
});

await test("evidence verification never derives a signer from the run context", () => {
	const evidence = readFileSync("scripts/verify-release-evidence.ts", "utf8");
	assert.doesNotMatch(evidence, /requiredEnvironment\("GITHUB_REPOSITORY/u);
	assert.doesNotMatch(evidence, /GITHUB_REPOSITORY_OWNER/u);
	assert.match(evidence, /releaseRepository\(release, process\.env\)/u);
	assert.match(evidence, /releaseOwner\(release, process\.env\)/u);
});

await test("the previous-release upgrade gate resolves the previous namespace per version", () => {
	const resolver = readFileSync("scripts/resolve-release-upgrade-images.ts", "utf8");
	assert.match(resolver, /releaseIdentityFor\(/u);
	assert.match(resolver, /currentReleaseIdentity\(\)/u);
	assert.doesNotMatch(resolver, /ghcr\.io\//u);
});
