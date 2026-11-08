import {
	ArtifactHttpError,
	artifactFailure,
	requestArtifact,
	type ArtifactPolicy,
	type ArtifactRequest,
} from "./artifact-http.ts";
import { matchesToolPin, toolSha256 } from "./ci-tool-integrity.ts";
import type { ToolSubject } from "./ci-tool-resolver.ts";
import { asArray, asRecord, asString, parseJson } from "./json.ts";
import { githubToken } from "./release-asset.ts";

const registry = "https://ghcr.io";
const manifestType = "application/vnd.oci.image.manifest.v1+json";
const artifactType = "application/vnd.hephaestus.ci-tool.v1";

export class CiToolRegistryError extends Error {
	constructor(operation: string, cause: unknown) {
		super(`GHCR ${operation} failed (${artifactFailure(cause)})`, { cause });
		this.name = "CiToolRegistryError";
	}
}

function repository(pin: ToolSubject): string {
	if (
		!/^[a-z0-9][a-z0-9-]*$/u.test(pin.tool) ||
		!/^[\w][\w.-]{0,127}$/u.test(pin.version) ||
		!/^[a-f0-9]{64}$/u.test(pin.sha256)
	) {
		throw new Error("Invalid CI tool registry identity");
	}
	return `hephaestus-build/ci-tools/${pin.tool}`;
}

function registryClient(repo: string, write: boolean, policy: ArtifactPolicy) {
	let bearer: string | undefined;
	async function authenticate() {
		try {
			const token = githubToken();
			const actor = process.env.GITHUB_ACTOR;
			const authenticated =
				token !== undefined && token !== "" && actor !== undefined && actor !== "";
			if (write && !authenticated) {
				throw new Error("Publishing CI tools needs GITHUB_TOKEN and GITHUB_ACTOR");
			}
			const credentials = authenticated
				? {
						header: `Basic ${Buffer.from(`${actor}:${token}`).toString("base64")}`,
						origins: [registry],
					}
				: undefined;
			const scope = encodeURIComponent(`repository:${repo}:${write ? "pull,push" : "pull"}`);
			const response = await requestArtifact(
				`${registry}/token?service=ghcr.io&scope=${scope}`,
				{ accept: "application/json", credentials },
				policy,
			);
			const value = asRecord(
				parseJson(new TextDecoder().decode(response.bytes)),
				"registry credentials",
			);
			bearer = asString(value.token ?? value.access_token, "registry token");
			if (!bearer) {
				throw new Error("Empty registry token");
			}
		} catch (error) {
			throw new CiToolRegistryError("authentication", error);
		}
	}
	return async (url: string, options: ArtifactRequest = {}) => {
		if (new URL(url).origin !== registry) {
			throw new Error("CI tool registry request left GHCR");
		}
		if (bearer === undefined) {
			await authenticate();
		}
		for (let attempt = 0; ; attempt += 1) {
			try {
				return await requestArtifact(
					url,
					{ ...options, credentials: { header: `Bearer ${bearer ?? ""}`, origins: [registry] } },
					policy,
				);
			} catch (error) {
				if (!(error instanceof ArtifactHttpError) || error.status !== 401 || attempt !== 0) {
					throw error;
				}
				await authenticate();
			}
		}
	};
}

/** GHCR grants a repository-scoped token. Storage redirects use their signed URL, never the repository bearer. */
export async function fetchMirroredCiTool(
	pin: ToolSubject,
	policy: ArtifactPolicy = {},
): Promise<Uint8Array> {
	const repo = repository(pin);
	const request = registryClient(repo, false, policy);
	const base = `${registry}/v2/${repo}`;
	const response = await request(`${base}/manifests/${pin.version}`, { accept: manifestType });
	const manifest = asRecord(
		parseJson(new TextDecoder().decode(response.bytes)),
		"CI tool manifest",
	);
	if (
		manifest.schemaVersion !== 2 ||
		manifest.mediaType !== manifestType ||
		manifest.artifactType !== artifactType
	) {
		throw new Error("Unexpected CI tool manifest");
	}
	const digest = `sha256:${pin.sha256}`;
	const layer = asArray(manifest.layers, "CI tool layers")
		.map((value) => asRecord(value, "CI tool layer"))
		.find((value) => value.digest === digest);
	if (layer === undefined) {
		throw new Error("The mirror does not contain the pinned checksum");
	}
	const blob = await request(`${base}/blobs/${digest}`);
	return blob.bytes;
}

export interface MirrorEntry {
	pin: ToolSubject;
	bytes: Uint8Array;
}

/** Publish a version only after every platform and auxiliary asset passed its owning checksum. */
export async function publishCiToolGroup(
	entries: readonly MirrorEntry[],
	policy: ArtifactPolicy = {},
): Promise<void> {
	const first = entries[0];
	if (first === undefined) {
		throw new Error("Empty CI tool mirror group");
	}
	const repo = repository(first.pin);
	const unique = new Map<string, MirrorEntry>();
	for (const entry of entries) {
		if (
			repository(entry.pin) !== repo ||
			entry.pin.version !== first.pin.version ||
			!matchesToolPin(entry.bytes, entry.pin)
		) {
			throw new Error("Refusing an unverified CI tool mirror group");
		}
		unique.set(entry.pin.sha256, entry);
	}
	const send = registryClient(repo, true, policy);
	async function request(url: string, options: ArtifactRequest, operation: string) {
		try {
			return await send(url, options);
		} catch (error) {
			if (error instanceof CiToolRegistryError) {
				throw error;
			}
			throw new CiToolRegistryError(operation, error);
		}
	}
	const base = `${registry}/v2/${repo}`;
	async function upload(bytes: Uint8Array) {
		const digest = `sha256:${toolSha256(bytes)}`;
		const existing = await request(
			`${base}/blobs/${digest}`,
			{
				method: "HEAD",
				acceptedStatuses: [404],
			},
			"blob lookup",
		);
		if (existing.status === 200) {
			return digest;
		}
		const started = await request(
			`${base}/blobs/uploads/`,
			{ method: "POST" },
			"upload initiation",
		);
		const location = started.headers.get("location");
		if (location === null) {
			throw new Error("GHCR did not return an upload location");
		}
		const destination = new URL(location, base);
		if (
			destination.origin !== registry ||
			destination.username ||
			destination.password ||
			![`/v2/${repo}/blobs/upload/`, `/v2/${repo}/blobs/uploads/`].some((prefix) =>
				destination.pathname.startsWith(prefix),
			)
		) {
			throw new CiToolRegistryError(
				"upload location validation",
				new Error("Unsafe upload location"),
			);
		}
		destination.searchParams.set("digest", digest);
		await request(
			destination.href,
			{
				method: "PUT",
				contentType: "application/octet-stream",
				body: bytes,
			},
			"blob upload",
		);
		return digest;
	}
	const config = new TextEncoder().encode("{}");
	const configDigest = await upload(config);
	const layers = [];
	for (const { pin, bytes } of unique.values()) {
		const digest = await upload(bytes);
		layers.push({
			mediaType: "application/octet-stream",
			digest,
			size: bytes.byteLength,
			annotations: { "org.opencontainers.image.title": pin.name },
		});
	}
	const manifest = {
		schemaVersion: 2,
		mediaType: manifestType,
		artifactType,
		config: {
			mediaType: "application/vnd.oci.empty.v1+json",
			digest: configDigest,
			size: config.byteLength,
		},
		layers,
		annotations: {
			"org.opencontainers.image.source": "https://github.com/hephaestus-build/Hephaestus",
			"org.opencontainers.image.version": first.pin.version,
		},
	};
	await request(
		`${base}/manifests/${first.pin.version}`,
		{
			method: "PUT",
			contentType: manifestType,
			body: new TextEncoder().encode(JSON.stringify(manifest)),
		},
		"manifest publication",
	);
}
