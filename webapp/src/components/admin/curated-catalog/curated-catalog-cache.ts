import type { CuratedCatalog } from "@/api/types.gen";

export function reorderCuratedGroups(
	catalog: CuratedCatalog,
	orderedSlugs: readonly string[],
): CuratedCatalog {
	const positions = new Map(orderedSlugs.map((slug, position) => [slug, position]));
	return {
		...catalog,
		groups: catalog.groups.map((group) => ({
			...group,
			position: positions.get(group.slug) ?? group.position,
		})),
	};
}

export function placeCuratedPractice(
	catalog: CuratedCatalog,
	practiceSlug: string,
	groupSlug: string | null,
	position: number,
): CuratedCatalog {
	const moved = catalog.practices.find((practice) => practice.slug === practiceSlug);
	if (!moved) {
		return catalog;
	}
	const sourceGroupSlug = moved.groupSlug ?? null;
	const source = orderedPractices(catalog, sourceGroupSlug).filter(
		(practice) => practice.slug !== practiceSlug,
	);
	const destination =
		sourceGroupSlug === groupSlug
			? source
			: orderedPractices(catalog, groupSlug).filter((practice) => practice.slug !== practiceSlug);
	destination.splice(Math.max(0, Math.min(position, destination.length)), 0, moved);

	const placements = new Map<string, { groupSlug: string | null; position: number }>();
	for (const [index, practice] of source.entries()) {
		placements.set(practice.slug, { groupSlug: sourceGroupSlug, position: index });
	}
	for (const [index, practice] of destination.entries()) {
		placements.set(practice.slug, { groupSlug, position: index });
	}
	const destinationGroupOffered =
		groupSlug === null ||
		catalog.groups.find((group) => group.slug === groupSlug)?.status.offered === true;

	return {
		...catalog,
		practices: catalog.practices.map((practice) => {
			const placement = placements.get(practice.slug);
			if (!placement) {
				return practice;
			}
			return {
				...practice,
				groupSlug: placement.groupSlug ?? undefined,
				position: placement.position,
				...(practice.slug === practiceSlug
					? { effectivelyOffered: practice.status.offered && destinationGroupOffered }
					: {}),
			};
		}),
	};
}

export function reorderCuratedPractices(
	catalog: CuratedCatalog,
	groupSlug: string | null,
	orderedSlugs: readonly string[],
): CuratedCatalog {
	const positions = new Map(orderedSlugs.map((slug, position) => [slug, position]));
	return {
		...catalog,
		practices: catalog.practices.map((practice) =>
			(practice.groupSlug ?? null) === groupSlug && positions.has(practice.slug)
				? { ...practice, position: positions.get(practice.slug) ?? practice.position }
				: practice,
		),
	};
}

export function orderedPracticeSlugs(catalog: CuratedCatalog, groupSlug: string | null): string[] {
	return orderedPractices(catalog, groupSlug).map((practice) => practice.slug);
}

function orderedPractices(catalog: CuratedCatalog, groupSlug: string | null) {
	return catalog.practices
		.filter((practice) => (practice.groupSlug ?? null) === groupSlug)
		.sort((a, b) => a.position - b.position || a.name.localeCompare(b.name));
}
