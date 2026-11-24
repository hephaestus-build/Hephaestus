/**
 * Verifies the image that `ossf/scorecard-action` runs, before Scorecard runs and on every pull
 * request that changes CI configuration.
 *
 * `.github/workflows/scorecard.yml` pins the action by commit (`uses: ossf/scorecard-action@<sha>`)
 * and its image as `SCORECARD_IMAGE: <repository>:<tag>@sha256:<digest>`. The action's
 * `action.yaml` names the image by tag only, so a check is needed for these facts:
 *
 * - the action at that commit runs `<repository>:<tag>`;
 * - the registry still resolves that tag to the pinned digest;
 * - the digest has a linux/amd64 image whose manifest, config and layers the registry serves, which
 *   is what a hosted runner pulls.
 *
 * Exits 1 and names each failed fact. docs/contributor/supply-chain-security.mdx § Scorecard image.
 */
import { readFile } from "node:fs/promises";

import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";

export const WORKFLOW = ".github/workflows/scorecard.yml";

export interface Pins {
	readonly commit: string;
	readonly repository: string;
	readonly tag: string;
	readonly digest: string;
}

export interface Manifest {
	readonly digest: string;
	readonly body: unknown;
}

/** What the check reads from the registry; `undefined` when the registry does not serve it. */
export interface Registry {
	manifest: (reference: string) => Promise<Manifest | undefined>;
	blob: (digest: string) => Promise<unknown>;
	hasBlob: (digest: string) => Promise<boolean>;
}

export function pinsOf(workflow: string): Pins {
	const commit = /uses: ossf\/scorecard-action@(?<commit>[a-f0-9]{40})\b/u.exec(workflow)?.groups
		?.commit;
	const image =
		/SCORECARD_IMAGE: (?<repository>[\w.-]+(?:\/[\w.-]+)+):(?<tag>[\w.-]+)@(?<digest>sha256:[a-f0-9]{64})\s*$/mu.exec(
			workflow,
		)?.groups;
	if (commit === undefined || image === undefined) {
		throw new Error(
			`${WORKFLOW} must pin ossf/scorecard-action by commit and SCORECARD_IMAGE by digest`,
		);
	}
	const { repository = "", tag = "", digest = "" } = image;
	return { commit, repository, tag, digest };
}

/** The image reference of a Docker action's `action.yaml`, without its `docker://` scheme. */
export function actionImage(actionYaml: string): string | undefined {
	return /^\s+image:\s*["']?docker:\/\/(?<image>[^"'\s]+)["']?\s*$/mu.exec(actionYaml)?.groups
		?.image;
}

const isIndex = (body: Record<string, unknown>) => Array.isArray(body.manifests);

async function linuxAmd64(
	registry: Registry,
	manifest: Manifest,
	problems: string[],
): Promise<Record<string, unknown> | undefined> {
	const body = asRecord(manifest.body, "manifest");
	if (!isIndex(body)) {
		return body;
	}
	const child = asArray(body.manifests, "index manifests")
		.map((entry) => asRecord(entry, "index entry"))
		.find((entry) => {
			const { platform } = entry;
			return isRecord(platform) && platform.os === "linux" && platform.architecture === "amd64";
		});
	if (child === undefined) {
		problems.push(`${manifest.digest} has no linux/amd64 image`);
		return undefined;
	}
	const digest = asString(child.digest, "index entry digest");
	const platformManifest = await registry.manifest(digest);
	if (platformManifest === undefined) {
		problems.push(`the registry does not serve the linux/amd64 manifest ${digest}`);
		return undefined;
	}
	return asRecord(platformManifest.body, "platform manifest");
}

/** Every failed fact; empty when the pinned image is the one the action runs and it pulls. */
export async function verify(
	pins: Pins,
	actionYaml: string,
	registry: Registry,
): Promise<string[]> {
	const problems: string[] = [];
	const expected = `${pins.repository}:${pins.tag}`;
	const named = actionImage(actionYaml);
	if (named !== expected) {
		problems.push(
			`ossf/scorecard-action@${pins.commit} runs ${named ?? "no docker:// image"}, not ${expected}`,
		);
	}
	const tagged = await registry.manifest(pins.tag);
	if (tagged === undefined) {
		problems.push(`the registry does not serve ${expected}`);
	} else if (tagged.digest !== pins.digest) {
		problems.push(`${expected} resolves to ${tagged.digest}, not the pinned ${pins.digest}`);
	}
	const pinned = await registry.manifest(pins.digest);
	if (pinned === undefined) {
		problems.push(`the registry does not serve ${pins.repository}@${pins.digest}`);
		return problems;
	}
	const image = await linuxAmd64(registry, pinned, problems);
	if (image === undefined) {
		return problems;
	}
	const configDigest = asString(asRecord(image.config, "image config").digest, "config digest");
	const config = asRecord(await registry.blob(configDigest), "image config blob");
	if (config.os !== "linux" || config.architecture !== "amd64") {
		problems.push(
			`${pins.digest} is ${String(config.os)}/${String(config.architecture)}, not linux/amd64`,
		);
	}
	for (const layer of asArray(image.layers, "image layers")) {
		const digest = asString(asRecord(layer, "layer").digest, "layer digest");
		if (!(await registry.hasBlob(digest))) {
			problems.push(`the registry does not serve layer ${digest}`);
		}
	}
	return problems;
}

const ACCEPT = [
	"application/vnd.oci.image.index.v1+json",
	"application/vnd.docker.distribution.manifest.list.v2+json",
	"application/vnd.oci.image.manifest.v1+json",
	"application/vnd.docker.distribution.manifest.v2+json",
].join(",");

/** An anonymous pull client for an OCI registry that issues bearer tokens, such as ghcr.io. */
export async function anonymousRegistry(repository: string): Promise<Registry> {
	const [host = "", ...path] = repository.split("/");
	const name = path.join("/");
	const tokenResponse = await fetch(
		`https://${host}/token?scope=${encodeURIComponent(`repository:${name}:pull`)}`,
	);
	if (!tokenResponse.ok) {
		throw new Error(`${host} refused an anonymous pull token: HTTP ${tokenResponse.status}`);
	}
	const token = asString(
		asRecord(parseJson(await tokenResponse.text()), "token response").token,
		"token",
	);
	const headers = { Authorization: `Bearer ${token}` };
	const base = `https://${host}/v2/${name}`;
	return {
		async manifest(reference) {
			const response = await fetch(`${base}/manifests/${reference}`, {
				headers: { ...headers, Accept: ACCEPT },
			});
			if (response.status === 404) {
				return;
			}
			if (!response.ok) {
				throw new Error(`${base}/manifests/${reference}: HTTP ${response.status}`);
			}
			const digest = response.headers.get("docker-content-digest") ?? "";
			return { digest, body: parseJson(await response.text()) };
		},
		async blob(digest) {
			const response = await fetch(`${base}/blobs/${digest}`, { headers });
			if (!response.ok) {
				throw new Error(`${base}/blobs/${digest}: HTTP ${response.status}`);
			}
			return parseJson(await response.text());
		},
		async hasBlob(digest) {
			const response = await fetch(`${base}/blobs/${digest}`, { method: "HEAD", headers });
			return response.ok;
		},
	};
}

if (import.meta.main) {
	const pins = pinsOf(await readFile(WORKFLOW, "utf8"));
	const actionResponse = await fetch(
		`https://raw.githubusercontent.com/ossf/scorecard-action/${pins.commit}/action.yaml`,
	);
	if (!actionResponse.ok) {
		throw new Error(
			`action.yaml of ossf/scorecard-action@${pins.commit}: HTTP ${actionResponse.status}`,
		);
	}
	const problems = await verify(
		pins,
		await actionResponse.text(),
		await anonymousRegistry(pins.repository),
	);
	for (const problem of problems) {
		process.stderr.write(`::error::${problem}\n`);
	}
	if (problems.length > 0) {
		process.exitCode = 1;
	} else {
		process.stdout.write(
			`ossf/scorecard-action@${pins.commit} runs ${pins.repository}:${pins.tag}@${pins.digest}, which pulls for linux/amd64.\n`,
		);
	}
}
