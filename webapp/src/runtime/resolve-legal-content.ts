import type { LegalPageId, ResolvedLegalContent } from "@/lib/legal";

interface Candidate {
	url: string;
	source: ResolvedLegalContent["source"];
}

const candidates = (page: LegalPageId): Candidate[] => [
	{ url: `/legal-overrides/${page}.md`, source: "override" },
	{ url: `/legal/_disclaimer/${page}.md`, source: "disclaimer" },
];

/**
 * A body that nobody reads keeps its request open until the response is collected, and the server sends a missing
 * file's answer in chunks: the page would never go quiet.
 */
async function discard(response: Response): Promise<void> {
	await response.body?.cancel().catch(() => undefined);
}

async function tryFetch(url: string, signal?: AbortSignal): Promise<string | null> {
	let response: Response;
	try {
		// oxlint-disable-next-line no-restricted-globals -- Static markdown the deployment drops into the SPA's own origin, not server data: no operation for it exists in `openapi.yaml`, or could.
		response = await fetch(url, { signal, cache: "no-cache" });
	} catch (error) {
		// Preserve abort semantics so the caller can distinguish teardown from
		// a network failure; everything else is "candidate missing, keep cascading".
		if (error instanceof DOMException && error.name === "AbortError") {
			throw error;
		}
		return null;
	}
	if (!response.ok) {
		await discard(response);
		return null;
	}
	// Defeat SPA fallbacks that served /index.html with a 200. The server answers a
	// missing legal file with a 404, but the client-side guard protects a reverse proxy
	// that rewrites misses to the page.
	const contentType = response.headers.get("content-type") ?? "";
	if (contentType.includes("text/html")) {
		await discard(response);
		return null;
	}
	const body = await response.text();
	if (!body.trim()) {
		return null;
	}
	if (body.trimStart().toLowerCase().startsWith("<!doctype html")) {
		return null;
	}
	return body;
}

export async function resolveLegalContent(
	page: LegalPageId,
	options: { signal?: AbortSignal } = {},
): Promise<ResolvedLegalContent> {
	for (const candidate of candidates(page)) {
		const markdown = await tryFetch(candidate.url, options.signal);
		if (markdown !== null) {
			return { markdown, source: candidate.source };
		}
	}
	// The disclaimer cascade terminates every call; reaching this line means the
	// shipped fallback is missing, which is a packaging bug, not a runtime state.
	throw new Error(`Unable to resolve legal content for page=${page}`);
}
