export type LegalPageId = "imprint" | "privacy";

export const LEGAL_PAGE_TITLES: Record<LegalPageId, string> = {
	imprint: "Imprint",
	privacy: "Privacy Statement",
};

export interface ResolvedLegalContent {
	markdown: string;
	source: "override" | "profile" | "disclaimer";
	profile: string;
}

// Profile names are joined into a URL path on the webapp origin, so we accept
// only the character class we ship filesystem directories in. Anything else
// (whitespace, `..`, `/`, ...) falls through to the disclaimer, mirroring the
// "unset profile" case — the resolver never constructs an unexpected URL.
// Kept in sync with webapp/docker/entrypoint.sh — a unit test pins the pair.
export const LEGAL_PROFILE_PATTERN_SOURCE = "^[a-z0-9][a-z0-9_-]{0,31}$";
const PROFILE_PATTERN = new RegExp(LEGAL_PROFILE_PATTERN_SOURCE, "u");

export function isValidLegalProfile(profile: string): boolean {
	return PROFILE_PATTERN.test(profile);
}

// Restrict hyperlink protocols in operator-supplied markdown. `javascript:`,
// `data:`, `vbscript:`, unknown schemes are silently dropped at render time.
// `\/(?!\/)` permits absolute same-origin paths like `/privacy` while rejecting
// scheme-relative URLs like `//evil.com/x` that would otherwise bypass the
// external-link target="_blank" + rel="noopener" hardening in SafeAnchor.
const SAFE_HREF_PATTERN = /^(?:https?:|mailto:|tel:|#|\/(?!\/))/iu;
const SAFE_IMG_SRC_PATTERN = /^(?:https?:|\/(?!\/))/iu;

export function isSafeLegalHref(href: unknown): href is string {
	return typeof href === "string" && SAFE_HREF_PATTERN.test(href);
}

export function isSafeLegalImageSrc(src: unknown): src is string {
	return typeof src === "string" && SAFE_IMG_SRC_PATTERN.test(src);
}

interface Candidate {
	url: string;
	source: ResolvedLegalContent["source"];
}

function buildCandidates(page: LegalPageId, profile: string): Candidate[] {
	const candidates: Candidate[] = [{ url: `/legal-overrides/${page}.md`, source: "override" }];
	if (profile) {
		candidates.push({ url: `/legal/profiles/${profile}/${page}.md`, source: "profile" });
	}
	candidates.push({ url: `/legal/_disclaimer/${page}.md`, source: "disclaimer" });
	return candidates;
}

async function tryFetch(url: string, signal?: AbortSignal): Promise<string | null> {
	let response: Response;
	try {
		// oxlint-disable-next-line no-restricted-globals -- Static markdown the deployment drops into the SPA's own origin, not application-server data: no operation for it exists in `openapi.yaml`, or could.
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
		return null;
	}
	// Defeat SPA fallbacks that served /index.html with a 200. nginx is configured
	// to 404 on missing legal files, but the client-side guard protects forks whose
	// reverse proxy is not (yet) configured that way.
	const contentType = response.headers.get("content-type") ?? "";
	if (contentType.includes("text/html")) {
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
	options: { signal?: AbortSignal; profile?: string } = {},
): Promise<ResolvedLegalContent> {
	const raw = (options.profile ?? "").trim();
	const profile = isValidLegalProfile(raw) ? raw : "";
	const candidates = buildCandidates(page, profile);
	for (const candidate of candidates) {
		const markdown = await tryFetch(candidate.url, options.signal);
		if (markdown !== null) {
			return { markdown, source: candidate.source, profile };
		}
	}
	// The disclaimer cascade terminates every call; reaching this line means the
	// shipped fallback is missing, which is a packaging bug, not a runtime state.
	throw new Error(`Unable to resolve legal content for page=${page}`);
}
