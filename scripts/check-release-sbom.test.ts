import assert from "node:assert/strict";
import { test } from "node:test";
import { validateReleaseSbom } from "./check-release-sbom.ts";

const repository = "ghcr.io/hephaestus-build/webapp";
const digest = "sha256:1102d43b320b1b9c06bdfc7f9e616c068abadace9b6874dedfcf41e1f35da3f5";
const indexDigest = "sha256:7baaa03d31d57baeb4c09f91d3579909e19b90e802e3e9afb148477edad4fe40";
/** What the daemon re-serialization of the same artifact digests to. */
const daemonDigest = "sha256:560d450d023441f2290fed7a93f06801bdbadae36e37d846681dd1332cbdf9c3";
const subject = { repository, digest, platform: "linux/amd64" };

const artifact = {
	name: "example",
	version: "1.2.3",
	type: "npm",
	purl: "pkg:npm/example@1.2.3",
	locations: [{ path: "/app/package.json" }],
	licenses: [{ value: "MIT" }],
};
const syft = {
	source: {
		type: "image",
		name: repository,
		version: digest,
		metadata: {
			userInput: `${repository}@${digest}`,
			manifestDigest: digest,
			mediaType: "application/vnd.oci.image.manifest.v1+json",
			os: "linux",
			architecture: "amd64",
			repoDigests: [`${repository}@${digest}`],
		},
	},
	artifacts: [artifact],
};
const spdx = {
	spdxVersion: "SPDX-2.3",
	dataLicense: "CC0-1.0",
	documentNamespace: "https://example.com/sbom",
	packages: [
		{
			name: repository,
			versionInfo: digest,
			SPDXID: "SPDXRef-DocumentRoot-Image-ghcr.io-hephaestus-build-webapp",
			primaryPackagePurpose: "CONTAINER",
		},
		{
			name: "example",
			versionInfo: "1.2.3",
			externalRefs: [{ referenceType: "purl", referenceLocator: artifact.purl }],
		},
	],
};
const cycloneDx = {
	bomFormat: "CycloneDX",
	specVersion: "1.7",
	metadata: { component: { type: "container", name: repository, version: digest } },
	components: [{ name: "example", version: "1.2.3", purl: artifact.purl }],
};

function withSourceMetadata(metadata: Record<string, unknown>): unknown {
	return {
		...syft,
		source: { ...syft.source, metadata: { ...syft.source.metadata, ...metadata } },
	};
}

await test("proves subject binding and lossless package conversion", () => {
	assert.deepEqual(validateReleaseSbom(syft, spdx, cycloneDx, subject), {
		schemaVersion: 1,
		digest,
		platform: "linux/amd64",
		packageCount: 1,
		packagesWithPurl: 1,
		packagesWithLicense: 1,
		packagesWithoutLicense: [],
	});
});

await test("rejects wrong subjects and packages lost during conversion", () => {
	assert.throws(
		() =>
			validateReleaseSbom(syft, spdx, cycloneDx, {
				...subject,
				digest: `sha256:${"b".repeat(64)}`,
			}),
		/wrong manifest/u,
	);
	assert.throws(
		() => validateReleaseSbom(syft, { ...spdx, packages: [] }, cycloneDx, subject),
		/SPDX document must describe exactly one container/u,
	);
	assert.throws(
		() => validateReleaseSbom(syft, spdx, { ...cycloneDx, components: [] }, subject),
		/CycloneDX output omitted/u,
	);
	assert.throws(
		() =>
			validateReleaseSbom(
				{ ...syft, artifacts: [{ ...artifact, purl: "pkg:npm/other@1.2.3" }] },
				spdx,
				cycloneDx,
				subject,
			),
		/SPDX output omitted/u,
	);
});

await test("rejects an SBOM taken from a Docker daemon copy of the artifact", () => {
	assert.throws(
		() =>
			validateReleaseSbom(
				withSourceMetadata({
					manifestDigest: daemonDigest,
					mediaType: "application/vnd.docker.distribution.manifest.v2+json",
				}),
				spdx,
				cycloneDx,
				subject,
			),
		/Syft SBOM is bound to the wrong manifest/u,
	);
});

await test("rejects an SBOM taken from the index instead of the platform manifest", () => {
	assert.throws(
		() =>
			validateReleaseSbom(
				withSourceMetadata({
					userInput: `${repository}@${indexDigest}`,
					repoDigests: [`${repository}@${indexDigest}`],
				}),
				spdx,
				cycloneDx,
				subject,
			),
		new RegExp(`Syft SBOM was not resolved from ${repository}@${digest}`, "u"),
	);
	assert.throws(
		() =>
			validateReleaseSbom(
				withSourceMetadata({
					repoDigests: [`${repository}@${digest}`, `${repository}@${indexDigest}`],
				}),
				spdx,
				cycloneDx,
				subject,
			),
		/also resolves a digest the subject does not name/u,
	);
	assert.throws(
		() => validateReleaseSbom(withSourceMetadata({ repoDigests: [] }), spdx, cycloneDx, subject),
		/Syft SBOM was not resolved from/u,
	);
});

await test("rejects an index, which describes more than one artifact", () => {
	assert.throws(
		() =>
			validateReleaseSbom(
				withSourceMetadata({ mediaType: "application/vnd.oci.image.index.v1+json" }),
				spdx,
				cycloneDx,
				subject,
			),
		/does not describe a single-platform image manifest/u,
	);
});

await test("rejects an SBOM for the same image in another namespace", () => {
	const previous = "ghcr.io/ls1intum/hephaestus/webapp";
	assert.throws(
		() =>
			validateReleaseSbom(
				{ ...syft, source: { ...syft.source, name: previous } },
				spdx,
				cycloneDx,
				subject,
			),
		/Syft SBOM is bound to the wrong repository/u,
	);
	assert.throws(
		() => validateReleaseSbom(syft, spdx, cycloneDx, { ...subject, repository: previous }),
		/Syft SBOM is bound to the wrong repository/u,
	);
});

await test("rejects an SBOM for another platform of the same image", () => {
	assert.throws(
		() =>
			validateReleaseSbom(withSourceMetadata({ architecture: "arm64" }), spdx, cycloneDx, subject),
		/Syft SBOM is bound to the wrong platform/u,
	);
	assert.throws(
		() => validateReleaseSbom(syft, spdx, cycloneDx, { ...subject, platform: "windows/amd64" }),
		/platform must be linux/u,
	);
});

await test("rejects SPDX and CycloneDX renderings of a different artifact", () => {
	const other = `sha256:${"c".repeat(64)}`;
	assert.throws(
		() =>
			validateReleaseSbom(
				syft,
				{ ...spdx, packages: [{ ...spdx.packages[0], versionInfo: other }, spdx.packages[1]] },
				cycloneDx,
				subject,
			),
		new RegExp(`SPDX document is not bound to ${repository}@${digest}`, "u"),
	);
	assert.throws(
		() =>
			validateReleaseSbom(
				syft,
				spdx,
				{
					...cycloneDx,
					metadata: { component: { type: "container", name: repository, version: other } },
				},
				subject,
			),
		new RegExp(`CycloneDX document is not bound to ${repository}@${digest}`, "u"),
	);
	assert.throws(
		() => validateReleaseSbom(syft, spdx, { ...cycloneDx, metadata: {} }, subject),
		/CycloneDX metadata component must be an object/u,
	);
});

await test("accepts Docker Hub's index.docker.io form of an upstream repository", () => {
	const upstream = "docker.io/library/alpine";
	const alpineDigest = "sha256:79ff19e9084a00eece421b2523fb93e22d730e2c0e525905de047e848e56d95f";
	const result = validateReleaseSbom(
		{
			...syft,
			source: {
				...syft.source,
				name: upstream,
				metadata: {
					...syft.source.metadata,
					manifestDigest: alpineDigest,
					repoDigests: [`index.docker.io/library/alpine@${alpineDigest}`],
				},
			},
		},
		{
			...spdx,
			packages: [
				{ ...spdx.packages[0], name: upstream, versionInfo: alpineDigest },
				spdx.packages[1],
			],
		},
		{
			...cycloneDx,
			metadata: { component: { type: "container", name: upstream, version: alpineDigest } },
		},
		{ repository: upstream, digest: alpineDigest, platform: "linux/amd64" },
	);
	assert.equal(result.digest, alpineDigest);
});

await test("records unknown licenses instead of pretending they are known", () => {
	const result = validateReleaseSbom(
		{ ...syft, artifacts: [{ ...artifact, licenses: [] }] },
		spdx,
		cycloneDx,
		subject,
	);
	assert.equal(result.packagesWithLicense, 0);
	assert.deepEqual(result.packagesWithoutLicense, [{ name: "example", version: "1.2.3" }]);
});
