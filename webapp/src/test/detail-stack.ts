import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { parseSearch } from "@/lib/router-search";

/**
 * The detail stack a link opens, read back with the app's own search parser, so an assertion
 * names levels (`observation:<id>`) rather than how the URL happens to encode them.
 */
export function levelsOpenedBy(link: HTMLElement): string[] {
	const search = parseSearch(
		new URL(String(link.getAttribute("href")), window.location.origin).search,
	);
	return stackInSearch(search.detail);
}
