import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import {
	assertBundledNpmInventory,
	assertDirectoryFormatsCover,
	assertBundledNpmScan,
	SERVER_WEB_APP_BUNDLE,
	WEBAPP_IMAGE_BUNDLE,
	componentEvidence,
	imagePath,
	validateReleaseSbom,
} from "./check-release-sbom.ts";
import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { asArray, asRecord } from "./lib/json.ts";

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

await test("reads Go binaries and file digests only from a Syft scan of the exact subject", () => {
	const hash = "1".repeat(64);
	const goSyft = {
		...syft,
		descriptor: {
			configuration: {
				catalogers: {
					used: ["go-module-binary-cataloger", "file-metadata-cataloger", "file-digest-cataloger"],
				},
				search: { scope: "squashed" },
			},
		},
		artifacts: [
			artifact,
			{
				...artifact,
				type: "go-module",
				foundBy: "go-module-binary-cataloger",
				locations: [
					{ path: "/usr/local/bin/helper", annotations: { evidence: "primary" } },
					{ path: "/usr/local/bin/ignored", annotations: { evidence: "supporting" } },
				],
			},
			// A go.mod read from source is not an executable the image runs.
			{
				...artifact,
				type: "go-module",
				foundBy: "go-module-file-cataloger",
				locations: [{ path: "/src/go.mod", annotations: { evidence: "primary" } }],
			},
		],
		files: [
			{
				location: { path: "/usr/local/bin/helper" },
				digests: [{ algorithm: "sha256", value: hash }],
			},
			{ location: { path: "/app/server.jar" }, digests: [{ algorithm: "sha1", value: "x" }] },
			{ location: { path: "/twice" }, digests: [{ algorithm: "sha256", value: hash }] },
			{ location: { path: "/twice" }, digests: [{ algorithm: "sha256", value: "2".repeat(64) }] },
		],
	};
	const evidence = componentEvidence(goSyft, subject);
	assert.deepEqual([...evidence.goBinaries], ["/usr/local/bin/helper"]);
	assert.equal(evidence.sha256.get("/usr/local/bin/helper"), hash);
	assert.equal(evidence.sha256.get("/app/server.jar"), null);
	assert.equal(evidence.sha256.get("/twice"), null, "different digests for one path are ambiguous");
	assert.throws(() => componentEvidence(syft, subject), /Syft descriptor/u);
	for (const missing of goSyft.descriptor.configuration.catalogers.used) {
		assert.throws(
			() =>
				componentEvidence(
					{
						...goSyft,
						descriptor: {
							configuration: {
								...goSyft.descriptor.configuration,
								catalogers: {
									used: goSyft.descriptor.configuration.catalogers.used.filter(
										(name) => name !== missing,
									),
								},
							},
						},
					},
					subject,
				),
			/did not run/u,
		);
	}
	assert.throws(() => componentEvidence({ ...goSyft, artifacts: [] }, subject), /no Go binary/u);
	assert.throws(
		() =>
			componentEvidence(
				{
					...goSyft,
					artifacts: [{ ...artifact, type: "go-module", foundBy: "go-module-binary-cataloger" }],
				},
				subject,
			),
		/no primary location/u,
	);
	assert.throws(
		() =>
			componentEvidence(
				{
					...goSyft,
					descriptor: {
						configuration: { ...goSyft.descriptor.configuration, search: { scope: "all-layers" } },
					},
				},
				subject,
			),
		/squashed/u,
	);
	assert.throws(
		() => componentEvidence(goSyft, { ...subject, platform: "linux/arm64" }),
		/wrong platform/u,
	);
	assert.throws(
		() =>
			componentEvidence(
				{ ...goSyft, files: [{ location: { path: "usr/local/bin/helper" } }] },
				subject,
			),
		/canonical absolute image path/u,
	);
	assert.equal(imagePath("usr/local/bin/helper"), "/usr/local/bin/helper");
	for (const bad of ["", "/a/../b", "a//b", "./a", String.raw`a\b`, "//a"]) {
		assert.equal(imagePath(bad), undefined, bad);
	}
});

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

const bundlePath = "/usr/share/nginx/html/.vite/packages/%40scope%2Flazy%401.2.3/package.json";
const scan = {
	files: [{ location: { path: bundlePath } }],
	artifacts: [
		{
			type: "npm",
			foundBy: "javascript-package-cataloger",
			name: "@scope/lazy",
			version: "1.2.3",
			locations: [{ path: bundlePath }],
		},
	],
};

await test("release capture requires every bundled identity from its image manifest", () => {
	assert.doesNotThrow(() => assertBundledNpmInventory(scan, WEBAPP_IMAGE_BUNDLE));
	assert.throws(
		() => assertBundledNpmInventory({ ...scan, files: [] }, WEBAPP_IMAGE_BUNDLE),
		/no bundled npm/u,
	);
	assert.throws(
		() => assertBundledNpmInventory({ ...scan, artifacts: [] }, WEBAPP_IMAGE_BUNDLE),
		/omitted/u,
	);
	assert.throws(
		() =>
			assertBundledNpmInventory(
				{
					...scan,
					artifacts: scan.artifacts.map((item) => ({ ...item, version: "1.2.4" })),
				},
				WEBAPP_IMAGE_BUNDLE,
			),
		/omitted/u,
	);
});

await test("independent validation rejects a present bundle manifest without its npm identity", () => {
	const manifest = "/usr/share/nginx/html/.vite/packages/example%401.2.3/package.json";
	const bundled = {
		...syft,
		files: [{ location: { path: manifest } }],
		artifacts: [
			{ ...artifact, foundBy: "javascript-package-cataloger", locations: [{ path: manifest }] },
		],
	};
	assert.doesNotThrow(() => validateReleaseSbom(bundled, spdx, cycloneDx, subject));
	assert.throws(
		() => validateReleaseSbom({ ...bundled, artifacts: [artifact] }, spdx, cycloneDx, subject),
		/omitted/u,
	);
});

await test("Trivy must retain every emitted npm identity at its image path", () => {
	const file = "/usr/share/nginx/html/.vite/packages/example%401.2.3/package.json";
	const inventory = { files: [{ location: { path: file } }] };
	const pkg = { Name: "example", Version: "1.2.3", FilePath: file.slice(1) };
	const report = { Type: "node-pkg" };
	assert.doesNotThrow(() =>
		assertBundledNpmScan(
			inventory,
			{ Results: [{ ...report, Packages: [pkg] }] },
			WEBAPP_IMAGE_BUNDLE,
		),
	);
	for (const packages of [
		[],
		[{ ...pkg, Version: "1.2.4" }],
		[{ ...pkg, FilePath: "different/package.json" }],
	]) {
		assert.throws(
			() =>
				assertBundledNpmScan(
					inventory,
					{ Results: [{ ...report, Packages: packages }] },
					WEBAPP_IMAGE_BUNDLE,
				),
			/Trivy omitted bundled npm/u,
		);
	}
});

/** The web app that the server image carries, as Syft lists it from the jar's extracted `web-app/` (ADR 0100). */
// A real `syft dir:` scan of a released server image's web app, by the pinned Syft, trimmed to four packages with
// every kept entry unedited. A fixture written by hand missed the empty-path root entry that `dir:` lists (#4848).
const recordedWebAppScan = asRecord(
	JSON.parse(await readFile("scripts/fixtures/syft-web-app-scan.json", "utf8")),
	"recorded web app scan",
);
const recorded = () => structuredClone(recordedWebAppScan);
const withoutPackage = (name: string) => {
	const trimmed = recorded();
	const artifacts = asArray(trimmed.artifacts, "artifacts").filter(
		(entry) => asRecord(entry, "artifact").name !== name,
	);
	const kept = new Set(
		artifacts.flatMap((entry) =>
			asArray(asRecord(entry, "artifact").locations, "locations").map(
				(location) => asRecord(location, "location").path,
			),
		),
	);
	const files = asArray(trimmed.files, "files").filter((file) => {
		const filePath = asRecord(asRecord(file, "file").location, "location").path;
		return (
			typeof filePath !== "string" || !filePath.endsWith("/package.json") || kept.has(filePath)
		);
	});
	return { ...trimmed, artifacts, files };
};

await test("the recorded web app scan comes from the pinned Syft", async () => {
	// A Syft update changes what a scan contains, so it records this fixture again.
	const pin = await ciToolPin("syft", "linux-x64");
	assert.equal(asRecord(recordedWebAppScan.descriptor, "descriptor").version, pin.version);
	// `syft dir:` lists the scan root first, with the empty path.
	const first = asRecord(asArray(recordedWebAppScan.files, "files")[0], "first file");
	assert.equal(asRecord(first.location, "location").path, "");
});

await test("the server's web app inventory fails when a package every build bundles is missing", () => {
	assert.doesNotThrow(() => assertBundledNpmInventory(recorded(), SERVER_WEB_APP_BUNDLE));
	// The silent drop: a scan that still lists packages, but not the web app's own.
	assert.throws(
		() => assertBundledNpmInventory(withoutPackage("react-dom"), SERVER_WEB_APP_BUNDLE),
		/no bundled react-dom/u,
	);
	// The scan root alone is no manifest: an inventory of only the root still has lost the bundle.
	const onlyRoot = recorded();
	onlyRoot.files = asArray(onlyRoot.files, "files").slice(0, 1);
	onlyRoot.artifacts = [];
	assert.throws(
		() => assertBundledNpmInventory(onlyRoot, SERVER_WEB_APP_BUNDLE),
		/no bundled npm package manifests/u,
	);
	assert.throws(
		() =>
			assertBundledNpmInventory(
				{ ...recorded(), files: [{ location: { path: 7 } }] },
				SERVER_WEB_APP_BUNDLE,
			),
		/file path must be a string/u,
	);
	// The image's own inventory never satisfies the web app's: it has no file under the web app's root.
	assert.throws(
		() => assertBundledNpmInventory(scan, SERVER_WEB_APP_BUNDLE),
		/no bundled npm package manifests/u,
	);
});

/** A Syft inventory of a directory scan and its renderings, as `syft dir:` writes them. */
const purlOf = (identity: string) => `pkg:npm/${identity}`;
const directoryInventory = {
	artifacts: [{ name: "react", version: "19.2.0", purl: purlOf("react@19.2.0") }],
};
const spdxOf = (purls: readonly string[]) => ({
	spdxVersion: "SPDX-2.3",
	dataLicense: "CC0-1.0",
	documentNamespace: "https://example.invalid/web-app",
	packages: purls.map((locator) => ({
		name: "react",
		versionInfo: "19.2.0",
		externalRefs: [{ referenceType: "purl", referenceLocator: locator }],
	})),
});
const cycloneDxOf = (purls: readonly string[]) => ({
	bomFormat: "CycloneDX",
	specVersion: "1.7",
	components: purls.map((purl) => ({ name: "react", version: "19.2.0", purl })),
});

await test("a directory scan's standard renderings must list every package that Syft found", () => {
	const purls = [purlOf("react@19.2.0")];
	assert.doesNotThrow(() =>
		assertDirectoryFormatsCover(directoryInventory, spdxOf(purls), cycloneDxOf(purls)),
	);
	assert.throws(
		() => assertDirectoryFormatsCover(directoryInventory, spdxOf([]), cycloneDxOf(purls)),
		/SPDX output omitted react@19.2.0/u,
	);
	assert.throws(
		() => assertDirectoryFormatsCover(directoryInventory, spdxOf(purls), cycloneDxOf([])),
		/CycloneDX output omitted react@19.2.0/u,
	);
});
