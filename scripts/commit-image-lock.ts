/** Resolve commit-tagged images and verify their build provenance before channel signing. */
import { setTimeout as delay } from "node:timers/promises";

import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, asStringArray, isRecord, readJsonFile } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";

export interface ImageInventory {
	readonly images: readonly string[];
	readonly upstream: readonly {
		readonly name: string;
		readonly repository: string;
		readonly digest: string;
	}[];
}

export const DIGEST = /^sha256:[0-9a-f]{64}$/u;

/** A whole commit, never an abbreviation, which a later object could come to share. */
export function isCommit(value: string): boolean {
	return /^[0-9a-f]{40}$/u.test(value);
}

export function environmentKey(image: string): string {
	return `HEPHAESTUS_IMAGE_${image.toUpperCase().replaceAll("-", "_")}`;
}

export function parseInventory(value: unknown): ImageInventory {
	const record = asRecord(value, "image inventory");
	return {
		images: asStringArray(record.images, "image inventory.images"),
		upstream: asArray(record.upstream, "image inventory.upstream").map((candidate, index) => {
			const label = `upstream[${index}]`;
			const entry = asRecord(candidate, label);
			const name = asString(entry.name, `${label}.name`);
			const digest = asString(entry.digest, `${label}.digest`);
			if (!DIGEST.test(digest)) {
				throw new Error(`upstream ${name} is not pinned by digest`);
			}
			return { name, repository: asString(entry.repository, `${label}.repository`), digest };
		}),
	};
}

export async function resolveImages(
	inventory: ImageInventory,
	commit: string,
	owner: string,
	resolve: (repository: string, commit: string) => Promise<string>,
): Promise<Record<string, string>> {
	if (!isCommit(commit)) {
		throw new Error(`expected a full commit, got ${commit}`);
	}
	const images: Record<string, string> = {};
	for (const entry of inventory.upstream) {
		images[environmentKey(entry.name)] = `${entry.repository}@${entry.digest}`;
	}
	for (const image of inventory.images) {
		const repository = `ghcr.io/${owner}/${image}`;
		const digest = await resolve(repository, commit);
		if (!DIGEST.test(digest)) {
			throw new Error(`${image} did not resolve to a digest at ${commit}`);
		}
		images[environmentKey(image)] = `${repository}@${digest}`;
	}
	return images;
}

export async function readInventory(path: string): Promise<ImageInventory> {
	return parseInventory(await readJsonFile(path));
}

/** The waits before the second and third inspection: three tries over about two minutes. */
const INSPECT_RETRY_DELAYS_MS = [30_000, 90_000];

/**
 * A registry read that can succeed when repeated: a manifest the CDN does not serve yet, an HTTP 5xx,
 * or a dropped connection. A digest mismatch is never transient: it means the content changed.
 */
export function isTransientRegistryFailure(error: unknown): boolean {
	const stderr = isRecord(error) && typeof error.stderr === "string" ? error.stderr : "";
	if (/digest mismatch|unexpected digest/iu.test(stderr)) {
		return false;
	}
	return /not found|\b5\d\d\b|bad gateway|service unavailable|gateway timeout|httpReadSeeker|failed open|connection (?:reset|refused)|i\/o timeout|TLS handshake timeout|unexpected EOF/iu.test(
		stderr,
	);
}

/** The manifest digest of `reference`, read again after a transient registry failure. */
export async function inspectDigest(
	reference: string,
	inspect: (reference: string) => Promise<string> = async (at) =>
		output("docker", ["buildx", "imagetools", "inspect", at, "--format", "{{.Manifest.Digest}}"]),
	wait: (milliseconds: number) => Promise<unknown> = delay,
): Promise<string> {
	const read = async () => {
		const inspected = await inspect(reference);
		return inspected.trim();
	};
	for (const milliseconds of INSPECT_RETRY_DELAYS_MS) {
		try {
			return await read();
		} catch (error) {
			if (!isTransientRegistryFailure(error)) {
				throw error;
			}
			console.warn(
				`${reference} could not be read; the next attempt is in ${milliseconds / 1000} s`,
			);
			await wait(milliseconds);
		}
	}
	return read();
}

export async function resolveAndVerify(repository: string, commit: string): Promise<string> {
	const ghRepository = requiredEnv(process.env, "GITHUB_REPOSITORY");

	const digest = await inspectDigest(`${repository}:${commit}`);

	await run(
		"gh",
		[
			"attestation",
			"verify",
			`oci://${repository}@${digest}`,
			"--bundle-from-oci",
			"--repo",
			ghRepository,
			"--signer-workflow",
			`${ghRepository}/.github/workflows/reusable-docker-build.yml`,
			"--deny-self-hosted-runners",
			// Reject an older attested image retagged onto the requested commit.
			"--source-digest",
			commit,
			"--predicate-type",
			"https://slsa.dev/provenance/v1",
		],
		{ stdin: "ignore", stdout: "ignore" },
	);
	return digest;
}
