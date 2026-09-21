/** Resolve commit-tagged images and verify their build provenance before channel signing. */
import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, asStringArray, readJsonFile } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { isCommit } from "./reconcile-deployment.ts";

export interface ImageInventory {
	readonly images: readonly string[];
	readonly upstream: readonly {
		readonly name: string;
		readonly repository: string;
		readonly digest: string;
	}[];
}

export const DIGEST = /^sha256:[0-9a-f]{64}$/u;

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

export async function resolveAndVerify(repository: string, commit: string): Promise<string> {
	const ghRepository = requiredEnv(process.env, "GITHUB_REPOSITORY");

	const inspected = await output("docker", [
		"buildx",
		"imagetools",
		"inspect",
		`${repository}:${commit}`,
		"--format",
		"{{.Manifest.Digest}}",
	]);
	const digest = inspected.trim();

	await run(
		"gh",
		[
			"attestation",
			"verify",
			`oci://${repository}@${digest}`,
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
