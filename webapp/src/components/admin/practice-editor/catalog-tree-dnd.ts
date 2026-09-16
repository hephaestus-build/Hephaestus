import { hasText } from "@/lib/text";

export interface CatalogDropTarget {
	groupSlug: string | null;
	position: number;
}

export function getCatalogDropTarget(
	entries: readonly { groupSlug?: string; displayOrder: number; name: string; slug: string }[],
	activeSlug: string,
	groupSlug: string | null,
	anchorSlug?: string,
	afterAnchor = false,
): CatalogDropTarget | null {
	if (anchorSlug === activeSlug) {
		return null;
	}
	const destination = entries
		.filter((entry) => entry.slug !== activeSlug && (entry.groupSlug ?? null) === groupSlug)
		.sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name));
	if (!hasText(anchorSlug)) {
		return { groupSlug, position: destination.length };
	}
	const anchorIndex = destination.findIndex((entry) => entry.slug === anchorSlug);
	if (anchorIndex === -1) {
		return null;
	}
	return { groupSlug, position: anchorIndex + (afterAnchor ? 1 : 0) };
}
