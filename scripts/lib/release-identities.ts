/**
 * Per-release image namespace and signing identity (issue #1599).
 *
 * GHCR packages do not transfer between organizations and Fulcio certificates are
 * immutable, so a release keeps the namespace and certificate identity it was
 * published under forever. `security/release-identities.json` records that history;
 * everything that resolves a *previous* release's images or verifies its lock must
 * go through this map instead of assuming the run's own identity.
 *
 * The last entry is the current identity. Inside CI its certificate identity is
 * derived from the run context (`release-signer.ts`), which keeps signing and
 * verification aligned across a future transfer without touching the map; the
 * recorded slug is the fallback for operators running outside CI.
 */
import { readFileSync } from "node:fs";
import path from "node:path";

import { asArray, asRecord, asString } from "./json.ts";
import { releaseSignerRepository } from "./release-signer.ts";

export interface ReleaseIdentity {
	firstVersion: string;
	namespace: string;
	certificateIdentityRepository: string;
}

const versionPattern = /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/u;
const namespacePattern = /^ghcr\.io\/[a-z0-9][a-z0-9._-]*(?:\/[a-z0-9][a-z0-9._-]*)*$/u;
const repositoryPattern = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u;

const identitiesFile = path.join(import.meta.dirname, "../../security/release-identities.json");

function versionCore(release: string): [number, number, number] {
	const bare = release.startsWith("v") ? release.slice(1) : release;
	const core = bare.split("-", 1)[0] ?? bare;
	if (!versionPattern.test(core)) {
		throw new Error(`'${release}' is not a release version (expected [v]X.Y.Z)`);
	}
	const [major = 0, minor = 0, patch = 0] = core.split(".").map(Number);
	return [major, minor, patch];
}

function compare(left: [number, number, number], right: [number, number, number]): number {
	for (const [index, part] of left.entries()) {
		const difference = part - (right[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

export function parseReleaseIdentities(value: unknown): ReleaseIdentity[] {
	const document = asRecord(value, "release identities");
	if (document.schemaVersion !== 1) {
		throw new Error("release identities must use schema version 1");
	}
	const entries = asArray(document.identities, "release identities entries").map(
		(entry, index): ReleaseIdentity => {
			const record = asRecord(entry, `identity ${index}`);
			const identity = {
				firstVersion: asString(record.firstVersion, `identity ${index} firstVersion`),
				namespace: asString(record.namespace, `identity ${index} namespace`),
				certificateIdentityRepository: asString(
					record.certificateIdentityRepository,
					`identity ${index} certificateIdentityRepository`,
				),
			};
			if (!versionPattern.test(identity.firstVersion)) {
				throw new Error(`identity ${index} firstVersion must be X.Y.Z`);
			}
			if (!namespacePattern.test(identity.namespace)) {
				throw new Error(
					`identity ${index} namespace must be a ghcr.io path without a trailing slash`,
				);
			}
			if (!repositoryPattern.test(identity.certificateIdentityRepository)) {
				throw new Error(`identity ${index} certificateIdentityRepository must be owner/name`);
			}
			return identity;
		},
	);
	if (entries.length === 0) {
		throw new Error("release identities must contain at least one entry");
	}
	if (entries[0]?.firstVersion !== "0.0.0") {
		throw new Error("the first release identity must start at 0.0.0 so every version resolves");
	}
	for (let index = 1; index < entries.length; index += 1) {
		const previous = entries[index - 1];
		const current = entries[index];
		if (
			previous === undefined ||
			current === undefined ||
			compare(versionCore(previous.firstVersion), versionCore(current.firstVersion)) >= 0
		) {
			throw new Error("release identities must be ordered by strictly ascending firstVersion");
		}
	}
	return entries;
}

export function loadReleaseIdentities(): ReleaseIdentity[] {
	return parseReleaseIdentities(JSON.parse(readFileSync(identitiesFile, "utf8")));
}

/** The identity a release (`0.74.3` or `v0.74.3`) was published under. */
export function releaseIdentityFor(
	release: string,
	identities: ReleaseIdentity[] = loadReleaseIdentities(),
): ReleaseIdentity {
	const version = versionCore(release);
	const match = identities.findLast(
		(entry) => compare(versionCore(entry.firstVersion), version) <= 0,
	);
	if (!match) {
		throw new Error(`no release identity covers ${release}`);
	}
	return match;
}

/** The identity new releases are published under: the map's final entry. */
export function currentReleaseIdentity(
	identities: ReleaseIdentity[] = loadReleaseIdentities(),
): ReleaseIdentity {
	const current = identities.at(-1);
	if (!current) {
		throw new Error("release identities must contain at least one entry");
	}
	return current;
}

/**
 * The `owner/repo` whose workflows signed the given release's artifacts. Historical
 * entries are pinned by the map; the current entry follows the run context so a fork
 * or future transfer verifies its own releases without editing the map. In CI the
 * run context is required rather than merely preferred — verifying a current release
 * against the map's fallback would hide a misconfigured environment.
 */
export function releaseRepository(
	release: string,
	environment: NodeJS.ProcessEnv,
	identities: ReleaseIdentity[] = loadReleaseIdentities(),
): string {
	const identity = releaseIdentityFor(release, identities);
	const { GITHUB_REPOSITORY, CI } = environment;
	const inCi =
		(GITHUB_REPOSITORY !== undefined && GITHUB_REPOSITORY !== "") ||
		(CI !== undefined && CI !== "");
	if (identity === identities.at(-1) && inCi) {
		return releaseSignerRepository(environment);
	}
	return identity.certificateIdentityRepository;
}

/** The owner half of {@link releaseRepository} — `gh attestation verify --owner`. */
export function releaseOwner(
	release: string,
	environment: NodeJS.ProcessEnv,
	identities: ReleaseIdentity[] = loadReleaseIdentities(),
): string {
	const [owner] = releaseRepository(release, environment, identities).split("/");
	if (owner === undefined || owner === "") {
		throw new Error(`could not derive an owner for ${release}`);
	}
	return owner;
}

/**
 * The cosign certificate identity of a workflow in the repository that cut the given
 * release. Defaults to `release.yml`, which signs the release lock; the image indexes
 * and their SBOM attestations are signed by `reusable-docker-build.yml`.
 */
export function releaseCertificateIdentity(
	release: string,
	environment: NodeJS.ProcessEnv,
	identities: ReleaseIdentity[] = loadReleaseIdentities(),
	workflow = "release.yml",
): string {
	const serverUrl = environment.GITHUB_SERVER_URL ?? "https://github.com";
	const repository = releaseRepository(release, environment, identities);
	return `${serverUrl}/${repository}/.github/workflows/${workflow}@refs/heads/main`;
}
