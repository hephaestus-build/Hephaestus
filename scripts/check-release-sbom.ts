import { writeFileSync } from "node:fs";
import { isSet } from "./lib/env.ts";
import { readJsonFileSync } from "./lib/json.ts";

type JsonObject = Record<string, unknown>;

function isJsonObject(value: unknown): value is JsonObject {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function object(value: unknown, label: string): JsonObject {
	if (!isJsonObject(value)) {
		throw new Error(`${label} must be an object`);
	}
	return value;
}

function array(value: unknown, label: string): unknown[] {
	if (!Array.isArray(value)) {
		throw new TypeError(`${label} must be an array`);
	}
	return value;
}

function text(value: unknown, label: string): string {
	if (typeof value !== "string" || value.length === 0) {
		throw new Error(`${label} must be a string`);
	}
	return value;
}

function packageKey(value: unknown, label: string): string {
	const item = object(value, label);
	return `${text(item.name, `${label}.name`)}\u0000${text(item.version, `${label}.version`)}`;
}

function purl(value: unknown): string | undefined {
	const item = object(value, "package");
	return typeof item.purl === "string" && item.purl.length > 0 ? item.purl : undefined;
}

/**
 * The media types of a *single* image manifest. An index (`…image.index.v1+json`,
 * `…manifest.list.v2+json`) describes several artifacts at once, so it can never be
 * the subject of a per-platform SBOM. Both single-manifest types stay valid: the
 * release publishes OCI manifests, upstream registries may still serve schema 2.
 */
const manifestMediaTypes = new Set([
	"application/vnd.oci.image.manifest.v1+json",
	"application/vnd.docker.distribution.manifest.v2+json",
]);

/** Syft normalizes Docker Hub to `index.docker.io`; the inventory says `docker.io`. */
function canonicalRepository(repository: string): string {
	return repository.startsWith("index.docker.io/")
		? `docker.io/${repository.slice("index.docker.io/".length)}`
		: repository;
}

/** The subject an SBOM triple must describe: one image, one platform, one digest. */
export interface ReleaseSbomSubject {
	repository: string;
	digest: string;
	platform: string;
}

/** The packages one SBOM rendering names, by purl where it has one and by name@version otherwise. */
interface PackageInventory {
	purls: Set<string>;
	keys: Set<string>;
}

function spdxInventory(spdxInput: unknown, repository: string, digest: string): PackageInventory {
	const spdx = object(spdxInput, "SPDX SBOM");
	if (spdx.spdxVersion !== "SPDX-2.3" || spdx.dataLicense !== "CC0-1.0") {
		throw new Error("invalid SPDX document metadata");
	}
	text(spdx.documentNamespace, "SPDX document namespace");
	const purls = new Set<string>();
	const keys = new Set<string>();
	const containers: JsonObject[] = [];
	for (const [index, value] of array(spdx.packages, "SPDX packages").entries()) {
		const item = object(value, `SPDX package ${index}`);
		if (item.primaryPackagePurpose === "CONTAINER") {
			containers.push(item);
		}
		if (typeof item.name === "string" && typeof item.versionInfo === "string") {
			keys.add(`${item.name}\u0000${item.versionInfo}`);
		}
		for (const ref of Array.isArray(item.externalRefs) ? item.externalRefs : []) {
			const external = object(ref, "SPDX external reference");
			if (external.referenceType === "purl" && typeof external.referenceLocator === "string") {
				purls.add(external.referenceLocator);
			}
		}
	}

	// Package parity alone would accept an SPDX rendering of a *different* image that
	// installs the same packages, so bind each document to the subject as well.
	const [container, ...extraContainers] = containers;
	if (container === undefined || extraContainers.length > 0) {
		throw new Error("SPDX document must describe exactly one container");
	}
	if (
		canonicalRepository(text(container.name, "SPDX container name")) !== repository ||
		container.versionInfo !== digest
	) {
		throw new Error(`SPDX document is not bound to ${repository}@${digest}`);
	}
	return { purls, keys };
}

function cycloneDxInventory(
	cycloneDxInput: unknown,
	repository: string,
	digest: string,
): PackageInventory {
	const cycloneDx = object(cycloneDxInput, "CycloneDX SBOM");
	if (cycloneDx.bomFormat !== "CycloneDX") {
		throw new Error("invalid CycloneDX format");
	}
	text(cycloneDx.specVersion, "CycloneDX spec version");
	const subject = object(
		object(cycloneDx.metadata, "CycloneDX metadata").component,
		"CycloneDX metadata component",
	);
	if (
		subject.type !== "container" ||
		canonicalRepository(text(subject.name, "CycloneDX component name")) !== repository ||
		subject.version !== digest
	) {
		throw new Error(`CycloneDX document is not bound to ${repository}@${digest}`);
	}
	const purls = new Set<string>();
	const keys = new Set<string>();
	for (const [index, value] of array(cycloneDx.components, "CycloneDX components").entries()) {
		const component = object(value, `CycloneDX component ${index}`);
		if (typeof component.name === "string" && typeof component.version === "string") {
			keys.add(`${component.name}\u0000${component.version}`);
		}
		if (typeof component.purl === "string") {
			purls.add(component.purl);
		}
	}
	return { purls, keys };
}

interface SyftBinding {
	repository: string;
	digest: string;
	os: string;
	architecture: string;
}

function assertSyftSource(syft: JsonObject, binding: SyftBinding): void {
	const { repository, digest, os, architecture } = binding;
	const reference = `${repository}@${digest}`;
	const source = object(syft.source, "Syft source");
	const metadata = object(source.metadata, "Syft source metadata");
	if (source.type !== "image") {
		throw new Error("Syft source is not an image");
	}
	if (canonicalRepository(text(source.name, "Syft source name")) !== repository) {
		throw new Error("Syft SBOM is bound to the wrong repository");
	}
	if (!manifestMediaTypes.has(text(metadata.mediaType, "Syft source media type"))) {
		throw new Error("Syft SBOM does not describe a single-platform image manifest");
	}
	// Only a registry scan digests the manifest the registry serves. Pulled through a
	// Docker daemon the same artifact is re-serialized as a schema-2 manifest whose
	// digest is a local accident, so the release scans with `syft --from registry`.
	if (metadata.manifestDigest !== digest) {
		throw new Error("Syft SBOM is bound to the wrong manifest");
	}
	if (metadata.os !== os || metadata.architecture !== architecture) {
		throw new Error("Syft SBOM is bound to the wrong platform");
	}
	// `manifestDigest` alone cannot tell a scan of `repository@<index digest>` — which
	// resolves to this platform's manifest — from a scan of the platform digest the
	// release records. `repoDigests` carries the reference Syft actually resolved.
	const repoDigests = array(metadata.repoDigests, "Syft source repository digests").map(
		(value, index) => canonicalRepository(text(value, `Syft repository digest ${index}`)),
	);
	if (!repoDigests.includes(reference)) {
		throw new Error(`Syft SBOM was not resolved from ${reference}`);
	}
	if (!repoDigests.every((entry) => entry.endsWith(`@${digest}`))) {
		throw new Error("Syft SBOM also resolves a digest the subject does not name");
	}
}

function syftArtifactInventory(artifacts: readonly unknown[]): {
	expectedPurls: Set<string>;
	missingLicenses: JsonObject[];
} {
	const expectedPurls = new Set<string>();
	const missingLicenses: JsonObject[] = [];
	for (const [index, value] of artifacts.entries()) {
		const artifact = object(value, `Syft artifact ${index}`);
		text(artifact.type, `Syft artifact ${index}.type`);
		packageKey(artifact, `Syft artifact ${index}`);
		const artifactPurl = purl(artifact);
		if (artifactPurl !== undefined) {
			expectedPurls.add(artifactPurl);
		}
		if (array(artifact.locations, `Syft artifact ${index}.locations`).length === 0) {
			throw new Error(`Syft artifact ${index} has no location evidence`);
		}
		if (array(artifact.licenses, `Syft artifact ${index}.licenses`).length === 0) {
			missingLicenses.push({
				name: text(artifact.name, "artifact.name"),
				version: text(artifact.version, "artifact.version"),
			});
		}
	}
	return { expectedPurls, missingLicenses };
}

function assertDerivedInventoriesCover(
	artifacts: readonly unknown[],
	spdx: PackageInventory,
	cycloneDx: PackageInventory,
): void {
	for (const value of artifacts) {
		const artifactPurl = purl(value);
		const key = packageKey(value, "Syft artifact");
		if (!(artifactPurl === undefined ? spdx.keys.has(key) : spdx.purls.has(artifactPurl))) {
			throw new Error(`SPDX output omitted ${key.replace("\u0000", "@")} from the Syft inventory`);
		}
		if (
			!(artifactPurl === undefined ? cycloneDx.keys.has(key) : cycloneDx.purls.has(artifactPurl))
		) {
			throw new Error(
				`CycloneDX output omitted ${key.replace("\u0000", "@")} from the Syft inventory`,
			);
		}
	}
}

export function validateReleaseSbom(
	syftInput: unknown,
	spdxInput: unknown,
	cycloneDxInput: unknown,
	subject: ReleaseSbomSubject,
): JsonObject {
	const { digest, platform } = subject;
	if (!/^sha256:[a-f0-9]{64}$/u.test(digest)) {
		throw new Error("subject digest is malformed");
	}
	const [os, architecture] = platform.split("/");
	if (os !== "linux" || !isSet(architecture)) {
		throw new Error("platform must be linux/<architecture>");
	}
	const repository = canonicalRepository(text(subject.repository, "subject repository"));

	const syft = object(syftInput, "Syft SBOM");
	assertSyftSource(syft, { repository, digest, os, architecture });

	const artifacts = array(syft.artifacts, "Syft artifacts");
	if (artifacts.length === 0) {
		throw new Error("Syft SBOM contains no packages");
	}
	const { expectedPurls, missingLicenses } = syftArtifactInventory(artifacts);

	const spdx = spdxInventory(spdxInput, repository, digest);
	const cycloneDx = cycloneDxInventory(cycloneDxInput, repository, digest);
	assertDerivedInventoriesCover(artifacts, spdx, cycloneDx);

	return {
		schemaVersion: 1,
		digest,
		platform,
		packageCount: artifacts.length,
		packagesWithPurl: expectedPurls.size,
		packagesWithLicense: artifacts.length - missingLicenses.length,
		packagesWithoutLicense: missingLicenses,
	};
}

if (import.meta.main) {
	const [syftPath, spdxPath, cycloneDxPath, repository, digest, platform, outputPath] =
		process.argv.slice(2);
	if (
		!isSet(syftPath) ||
		!isSet(spdxPath) ||
		!isSet(cycloneDxPath) ||
		!isSet(repository) ||
		!isSet(digest) ||
		!isSet(platform) ||
		!isSet(outputPath)
	) {
		throw new Error(
			"usage: check-release-sbom <syft> <spdx> <cyclonedx> <repository> <digest> <platform> <output>",
		);
	}
	writeFileSync(
		outputPath,
		`${JSON.stringify(validateReleaseSbom(readJsonFileSync(syftPath), readJsonFileSync(spdxPath), readJsonFileSync(cycloneDxPath), { repository, digest, platform }), null, 2)}\n`,
	);
}
