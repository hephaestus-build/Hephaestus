import { artifactFailure, requestArtifact, type ArtifactPolicy } from "./artifact-http.ts";
import { asArray, asRecord, asString, parseJson } from "./json.ts";

export type AssetDownloadPolicy = ArtifactPolicy;
export const downloadFailure = artifactFailure;
const githubOrigins = ["https://api.github.com", "https://github.com"];

export function githubToken(): string | undefined {
	return [process.env.GITHUB_TOKEN, process.env.GH_TOKEN].find(
		(value) => value !== undefined && value !== "",
	);
}

async function download(
	url: string,
	token: string | undefined,
	accept: string,
	policy: AssetDownloadPolicy,
) {
	const result = await requestArtifact(
		url,
		{
			accept,
			...(token === undefined
				? {}
				: { credentials: { header: `Bearer ${token}`, origins: githubOrigins } }),
		},
		policy,
	);
	return result.bytes;
}

export async function fetchReleaseAssetDirect(
	url: string,
	policy: AssetDownloadPolicy = {},
): Promise<Uint8Array> {
	return download(
		url,
		githubOrigins.includes(new URL(url).origin) ? githubToken() : undefined,
		"application/octet-stream",
		policy,
	);
}

async function apiAssetLocation(
	url: string,
	credential: string | undefined,
	policy: AssetDownloadPolicy,
) {
	const raw =
		/^https:\/\/raw\.githubusercontent\.com\/(?<owner>[\w.-]+)\/(?<repository>[\w.-]+)\/(?<ref>[^/]+)\/(?<path>[^?#]+)$/u.exec(
			url,
		)?.groups;
	if (raw !== undefined) {
		return {
			url: `https://api.github.com/repos/${asString(raw.owner, "owner")}/${asString(raw.repository, "repository")}/contents/${asString(raw.path, "path")}?ref=${encodeURIComponent(asString(raw.ref, "ref"))}`,
			accept: "application/vnd.github.raw+json",
		};
	}
	const release =
		/^https:\/\/github\.com\/(?<owner>[\w.-]+)\/(?<repository>[\w.-]+)\/releases\/download\/(?<tag>[^/]+)\/(?<name>[^/?#]+)$/u.exec(
			url,
		)?.groups;
	if (release === undefined) {
		throw new Error("No GitHub API source for this asset");
	}
	const owner = asString(release.owner, "owner");
	const repository = asString(release.repository, "repository");
	const tag = asString(release.tag, "release tag");
	const name = asString(release.name, "release asset");
	const base = `https://api.github.com/repos/${owner}/${repository}/releases`;
	const bytes = await download(`${base}/tags/${tag}`, credential, "application/vnd.github+json", {
		...policy,
		timeoutMs: Math.min(policy.timeoutMs ?? 30_000, 10_000),
	});
	const metadata = asRecord(parseJson(new TextDecoder().decode(bytes)), "release");
	if (metadata.tag_name !== decodeURIComponent(tag) || metadata.draft !== false) {
		throw new Error("Unexpected release tag");
	}
	const matches = asArray(metadata.assets, "release assets")
		.map((asset) => asRecord(asset, "asset"))
		.filter((asset) => asset.name === decodeURIComponent(name));
	const asset = matches.length === 1 ? matches[0] : undefined;
	if (
		asset === undefined ||
		!Number.isSafeInteger(asset.id) ||
		typeof asset.id !== "number" ||
		asset.id < 1 ||
		asString(asset.state, "asset state") !== "uploaded"
	) {
		throw new Error("Pinned release asset is unavailable");
	}
	return { url: `${base}/assets/${String(asset.id)}`, accept: "application/octet-stream" };
}

/** Authenticated API first, then the same public API without repository-scoped credentials. */
export async function fetchReleaseAssetApi(
	url: string,
	policy: AssetDownloadPolicy = {},
): Promise<Uint8Array> {
	const token = githubToken();
	const credentials = token !== undefined && token !== "" ? [token, undefined] : [undefined];
	let failure: unknown;
	for (const credential of credentials) {
		let stage = "release metadata";
		try {
			const location = await apiAssetLocation(url, credential, policy);
			stage = "release asset";
			return await download(location.url, credential, location.accept, policy);
		} catch (error) {
			failure = error;
			console.warn(
				`GitHub ${credential === undefined ? "public" : "authenticated"} API ${stage} failed (${downloadFailure(error)}).`,
			);
		}
	}
	throw failure;
}

/** Unpinned release documents have their own downstream signature checks. Tool binaries use resolveCiTool. */
export async function downloadReleaseAsset(url: string): Promise<Uint8Array> {
	try {
		return await fetchReleaseAssetApi(url);
	} catch {
		return fetchReleaseAssetDirect(url);
	}
}
