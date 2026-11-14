export type LegalPageId = "imprint" | "privacy";

export const LEGAL_PAGE_TITLES: Record<LegalPageId, string> = {
	imprint: "Imprint",
	privacy: "Privacy notice",
};

export interface ResolvedLegalContent {
	markdown: string;
	source: "override" | "disclaimer";
}

/** Loads a page's markdown. The route supplies it, so a component never fetches. */
export type LegalContentResolver = (
	page: LegalPageId,
	options?: { signal?: AbortSignal },
) => Promise<ResolvedLegalContent>;

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
