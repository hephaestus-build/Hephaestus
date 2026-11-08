import { setTimeout as sleep } from "node:timers/promises";

import { asArray, asRecord, asString, parseJson } from "./json.ts";

function transportFailure(error: unknown): boolean {
	return (
		error instanceof TypeError ||
		(error instanceof DOMException && ["AbortError", "TimeoutError"].includes(error.name))
	);
}

/** A bounded download. Credentials stay on api.github.com, including across redirects. */
async function download(
	url: string,
	token?: string,
	accept = "application/octet-stream",
	timeout = 120_000,
): Promise<Uint8Array> {
	for (let attempt = 1; ; attempt += 1) {
		try {
			const headers: Record<string, string> = { Accept: accept };
			if (token !== undefined) {
				headers.Authorization = `Bearer ${token}`;
			}
			const signal = AbortSignal.timeout(timeout);
			let response = await fetch(url, { headers, signal, redirect: "manual" });
			let current = url;
			for (let redirects = 0; [301, 302, 303, 307, 308].includes(response.status); redirects += 1) {
				const location = response.headers.get("location");
				await response.body?.cancel();
				if (redirects === 5 || location === null) {
					throw new Error("Invalid release asset redirect");
				}
				const target = new URL(location, current);
				if (target.protocol !== "https:" || target.username || target.password) {
					throw new Error("Unsafe release asset redirect");
				}
				current = target.href;
				response = await fetch(target, { headers: { Accept: accept }, signal, redirect: "manual" });
			}
			if (response.ok) {
				return new Uint8Array(await response.arrayBuffer());
			}
			await response.body?.cancel();
			if (![408, 429, 500, 502, 503, 504].includes(response.status) || attempt === 3) {
				throw new Error(
					`Release asset download returned ${response.status} after ${attempt} attempt(s)`,
				);
			}
		} catch (error) {
			if (!transportFailure(error) || attempt === 3) {
				throw error;
			}
		}
		console.warn(`Release asset download unavailable; retrying (${attempt + 1}/3).`);
		await sleep(attempt * 1000);
	}
}

/** The caller still verifies pinned checksums or signatures before using these bytes. */
export async function downloadReleaseAsset(url: string): Promise<Uint8Array> {
	const release =
		/^https:\/\/github\.com\/(?<owner>[\w.-]+)\/(?<repository>[\w.-]+)\/releases\/download\/(?<tag>[^/]+)\/(?<name>[^/?#]+)$/u.exec(
			url,
		)?.groups;
	const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
	if (release !== undefined && token !== undefined && token !== "") {
		const owner = asString(release.owner, "release owner");
		const repository = asString(release.repository, "release repository");
		const tag = asString(release.tag, "release tag");
		const name = asString(release.name, "release asset");
		try {
			const base = `https://api.github.com/repos/${owner}/${repository}/releases`;
			const metadata = asRecord(
				parseJson(
					new TextDecoder().decode(
						await download(`${base}/tags/${tag}`, token, "application/vnd.github+json", 10_000),
					),
				),
				"release",
			);
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
			return await download(`${base}/assets/${String(asset.id)}`, token);
		} catch {
			console.warn("GitHub API release asset unavailable; trying its direct release URL.");
		}
	}
	return download(url);
}
