import { defaultParseSearch } from "@tanstack/react-router";

/**
 * The detail stack a link opens, read back with the router's own search parser, so an assertion
 * names levels (`observation:<id>`) rather than how the URL happens to encode them.
 */
export function levelsOpenedBy(link: HTMLElement): unknown {
	const search: Record<string, unknown> = defaultParseSearch(
		new URL(String(link.getAttribute("href")), window.location.origin).search,
	);
	return search.detail;
}
