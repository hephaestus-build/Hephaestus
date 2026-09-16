import type {
	AutonomyAssignment,
	AutonomyRollup,
	Practice,
	PracticeAutomatedReviewPolicy,
} from "@/api/types.gen";

export const UNASSIGNED_GROUP_KEY = "__unassigned__";

export interface AutonomyGroup {
	key: string;
	groupSlug: string | null;
	name: string;
	autonomy: AutonomyAssignment;
	counts: Record<string, number>;
	overriddenCount: number;
	practices: Practice[];
	totalPractices: number;
}

export function reviewableByHephaestus(policy: PracticeAutomatedReviewPolicy): boolean {
	return (
		policy.automatedReview.mode === "LANGUAGE_MODEL" &&
		policy.automatedReview.evidenceSufficiency === "SUFFICIENT_WHEN_REQUIREMENTS_MET"
	);
}

export function isOverridden(assignment: AutonomyAssignment): boolean {
	return !assignment.inherited;
}

export interface GroupOptions {
	overridesOnly?: boolean;
}

export function groupPracticesByGroup(
	rollup: AutonomyRollup,
	practices: readonly Practice[],
	{ overridesOnly = false }: GroupOptions = {},
): AutonomyGroup[] {
	const byGroup = new Map<string, Practice[]>();
	for (const practice of practices) {
		const key = practice.groupSlug ?? UNASSIGNED_GROUP_KEY;
		const bucket = byGroup.get(key);
		if (bucket) {
			bucket.push(practice);
		} else {
			byGroup.set(key, [practice]);
		}
	}

	const groups: AutonomyGroup[] = [];
	for (const group of rollup.groups) {
		const key = group.groupSlug ?? UNASSIGNED_GROUP_KEY;
		const owned = byGroup.get(key) ?? [];
		byGroup.delete(key);
		groups.push({
			key,
			groupSlug: group.groupSlug ?? null,
			name: group.groupName ?? "Unassigned",
			autonomy: group.autonomy,
			counts: group.counts,
			overriddenCount: group.overriddenCount,
			practices: owned,
			totalPractices: owned.length,
		});
	}

	for (const [key, owned] of byGroup) {
		groups.push({
			key,
			groupSlug: key === UNASSIGNED_GROUP_KEY ? null : key,
			name: key === UNASSIGNED_GROUP_KEY ? "Unassigned" : key,
			autonomy: rollup.workspaceDefault,
			counts: countByAutonomy(owned),
			overriddenCount: owned.filter((practice) => isOverridden(practice.autonomy)).length,
			practices: owned,
			totalPractices: owned.length,
		});
	}

	if (!overridesOnly) {
		return groups;
	}

	return groups
		.map((group) => ({
			...group,
			practices: group.practices.filter((practice) => isOverridden(practice.autonomy)),
		}))
		.filter(
			(group) =>
				group.practices.length > 0 || (group.groupSlug !== null && isOverridden(group.autonomy)),
		);
}

function countByAutonomy(practices: readonly Practice[]): Record<string, number> {
	const counts: Record<string, number> = {};
	for (const practice of practices) {
		const autonomy = practice.autonomy.effective;
		counts[autonomy] = (counts[autonomy] ?? 0) + 1;
	}
	return counts;
}

export function countOverrides(rollup: AutonomyRollup): {
	practices: number;
	groups: number;
} {
	return {
		practices: rollup.groups.reduce((total, group) => total + group.overriddenCount, 0),
		groups: rollup.groups.filter((group) => group.groupSlug != null && isOverridden(group.autonomy))
			.length,
	};
}
