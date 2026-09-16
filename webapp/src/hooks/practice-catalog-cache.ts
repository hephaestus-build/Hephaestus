import type {
	Practice,
	PracticeGroup,
	UpdatePracticeGroupRequest,
	UpdatePracticeRequest,
} from "@/api/types.gen";

export function practiceCatalogStructureScope(workspaceSlug: string) {
	return { id: `practice-catalog:${workspaceSlug}:structure` };
}

export function applyDisplayOrder<T extends { displayOrder: number; slug: string }>(
	items: T[],
	orderedSlugs: string[],
): T[] {
	const orderBySlug = new Map(orderedSlugs.map((slug, index) => [slug, index]));
	return items.map((item) => {
		const displayOrder = orderBySlug.get(item.slug);
		return displayOrder === undefined ? item : { ...item, displayOrder };
	});
}

function replaceGroup(groups: PracticeGroup[], updated: PracticeGroup): PracticeGroup[] {
	return groups.map((group) => (group.slug === updated.slug ? updated : group));
}

export function upsertGroup(groups: PracticeGroup[], updated: PracticeGroup): PracticeGroup[] {
	return groups.some((group) => group.slug === updated.slug)
		? replaceGroup(groups, updated)
		: [...groups, updated];
}

export function patchGroup(
	groups: PracticeGroup[],
	slug: string,
	patch: UpdatePracticeGroupRequest,
): PracticeGroup[] {
	return groups.map((group) => (group.slug === slug ? { ...group, ...patch } : group));
}

export function selectGroupPatch(
	group: PracticeGroup,
	request: UpdatePracticeGroupRequest,
): UpdatePracticeGroupRequest {
	return {
		...(request.visibleInPracticeDashboards === undefined
			? {}
			: { visibleInPracticeDashboards: group.visibleInPracticeDashboards }),
		...("color" in request ? { color: group.color } : {}),
		...("description" in request ? { description: group.description } : {}),
		...("displayOrder" in request ? { displayOrder: group.displayOrder } : {}),
		...("icon" in request ? { icon: group.icon } : {}),
		...("name" in request ? { name: group.name } : {}),
	};
}

function replacePractice(practices: Practice[], updated: Practice): Practice[] {
	return practices.map((practice) => (practice.slug === updated.slug ? updated : practice));
}

export function upsertPractice(practices: Practice[], updated: Practice): Practice[] {
	return practices.some((practice) => practice.slug === updated.slug)
		? replacePractice(practices, updated)
		: [...practices, updated];
}

export function patchPractice(
	practices: Practice[],
	slug: string,
	patch: Partial<Practice>,
): Practice[] {
	return practices.map((practice) =>
		practice.slug === slug ? { ...practice, ...patch } : practice,
	);
}

export function selectPracticePatch(
	practice: Practice,
	request: UpdatePracticeRequest,
): Partial<Practice> {
	const clear = new Set(request.clear);
	return {
		// The kind of work is read off the bindings server-side, so replacing them can move it — and can
		// therefore swap in a different work type's recommended review settings.
		...("bindings" in request
			? { bindings: practice.bindings, artifactKind: practice.artifactKind }
			: {}),
		...("criteria" in request ? { criteria: practice.criteria } : {}),
		...("automatedReviewPolicy" in request || "bindings" in request
			? {
					automatedReviewPolicy: practice.automatedReviewPolicy,
					automatedReviewValidation: practice.automatedReviewValidation,
				}
			: {}),
		...("name" in request ? { name: practice.name } : {}),
		...("precomputeScript" in request || clear.has("PRECOMPUTE_SCRIPT")
			? { precomputeScript: practice.precomputeScript }
			: {}),
		...("whatGoodLooksLike" in request || clear.has("WHAT_GOOD_LOOKS_LIKE")
			? { whatGoodLooksLike: practice.whatGoodLooksLike }
			: {}),
		...("whyItMatters" in request || clear.has("WHY_IT_MATTERS")
			? { whyItMatters: practice.whyItMatters }
			: {}),
		...("group" in request
			? { groupSlug: practice.groupSlug, displayOrder: practice.displayOrder }
			: {}),
	};
}

export type PracticePlacement = Pick<Practice, "groupSlug" | "displayOrder" | "slug">;

function byOrder(a: Practice, b: Practice) {
	return a.displayOrder - b.displayOrder || a.name.localeCompare(b.name);
}

export function placePractice(
	practices: Practice[],
	slug: string,
	groupSlug: string | null,
	position: number,
): Practice[] {
	const moving = practices.find((practice) => practice.slug === slug);
	if (!moving) {
		return practices;
	}

	const sourceGroupSlug = moving.groupSlug ?? null;
	const inGroup = (candidate: Practice, candidateGroupSlug: string | null) =>
		candidate.slug !== slug && (candidate.groupSlug ?? null) === candidateGroupSlug;
	const source = practices.filter((practice) => inGroup(practice, sourceGroupSlug)).sort(byOrder);
	const destination =
		sourceGroupSlug === groupSlug
			? source
			: practices.filter((practice) => inGroup(practice, groupSlug)).sort(byOrder);
	destination.splice(Math.min(position, destination.length), 0, {
		...moving,
		groupSlug: groupSlug ?? undefined,
	});

	const placements = [
		...(sourceGroupSlug === groupSlug
			? []
			: source.map((practice, displayOrder) => ({
					slug: practice.slug,
					groupSlug: sourceGroupSlug ?? undefined,
					displayOrder,
				}))),
		...destination.map((practice, displayOrder) => ({
			slug: practice.slug,
			groupSlug: groupSlug ?? undefined,
			displayOrder,
		})),
	];
	return applyPracticePlacements(practices, placements);
}

export function practicePlacementSnapshot(
	practices: Practice[],
	practiceSlug: string,
	destinationGroupSlug: string | null,
): PracticePlacement[] {
	const sourceGroupSlug =
		practices.find((practice) => practice.slug === practiceSlug)?.groupSlug ?? null;
	return practices
		.filter((practice) => {
			const groupSlug = practice.groupSlug ?? null;
			return groupSlug === sourceGroupSlug || groupSlug === destinationGroupSlug;
		})
		.map(({ slug, groupSlug, displayOrder }) => ({ slug, groupSlug, displayOrder }));
}

export function applyPracticePlacements(
	practices: Practice[],
	placements: PracticePlacement[],
): Practice[] {
	const bySlug = new Map(placements.map((placement) => [placement.slug, placement]));
	return practices.map((practice) => {
		const placement = bySlug.get(practice.slug);
		return placement
			? {
					...practice,
					groupSlug: placement.groupSlug,
					displayOrder: placement.displayOrder,
				}
			: practice;
	});
}

export function removeGroup(groups: PracticeGroup[], slug: string): PracticeGroup[] {
	return groups.filter((group) => group.slug !== slug);
}

export function unassignPractices(practices: Practice[], groupSlug: string): Practice[] {
	const firstDisplayOrder =
		Math.max(
			-1,
			...practices
				.filter((practice) => practice.groupSlug == null)
				.map((practice) => practice.displayOrder),
		) + 1;
	let offset = 0;
	return practices.map((practice) => {
		if (practice.groupSlug !== groupSlug) {
			return practice;
		}
		const displayOrder = firstDisplayOrder + offset;
		offset += 1;
		return { ...practice, groupSlug: undefined, displayOrder };
	});
}
