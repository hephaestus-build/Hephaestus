import type {
	AutonomyAssignment,
	AutonomyRollup,
	GroupAutonomyRollup,
	Practice,
	PracticeReviewSettings,
} from "@/api/types.gen";
import { mockReviewSettings } from "@/components/admin/practices/fixtures";
import type { PracticeAutonomy } from "@/lib/practice-autonomy";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";

/** Builds internally consistent settings, rollup, and practice fixtures from the inheritance inputs. */

export interface PracticeSpec {
	name: string;
	override?: PracticeAutonomy;
	reviewable?: boolean;
	whyItMatters?: string;
	artifactKind?: string;
}

export interface GroupSpec {
	slug: string | null;
	name: string | null;
	override?: PracticeAutonomy;
	practices: PracticeSpec[];
}

export interface AutonomyFixture {
	settings: PracticeReviewSettings;
	rollup: AutonomyRollup;
	practices: Practice[];
}

const emptyCounts = (): Record<PracticeAutonomy, number> => ({
	OFF: 0,
	HUMAN_APPROVAL: 0,
	AUTOMATIC: 0,
});

function assignment(
	override: PracticeAutonomy | undefined,
	effective: PracticeAutonomy,
	source: AutonomyAssignment["source"],
): AutonomyAssignment {
	return { effective, override, source, inherited: override == null };
}

export function buildAutonomyFixture({
	workspaceDefault,
	groups,
}: {
	/** Absent means this workspace has never chosen, so Review before sending applies. */
	workspaceDefault?: PracticeAutonomy;
	groups: GroupSpec[];
}): AutonomyFixture {
	const effectiveDefault: PracticeAutonomy = workspaceDefault ?? "HUMAN_APPROVAL";
	const practices: Practice[] = [];
	const rollupGroups: GroupAutonomyRollup[] = [];
	const workspaceCounts = emptyCounts();
	let id = 1;

	for (const group of groups) {
		const groupEffective = group.override ?? effectiveDefault;
		const counts = emptyCounts();
		let overriddenCount = 0;

		for (const spec of group.practices) {
			// The server writes Off onto a practice it cannot review, rather than letting it inherit.
			const held = spec.reviewable === false ? "OFF" : spec.override;
			const effective = held ?? groupEffective;
			let source: AutonomyAssignment["source"] = group.override ? "GROUP" : "WORKSPACE";
			if (held) {
				source = "PRACTICE";
				overriddenCount += 1;
			}
			counts[effective] += 1;
			workspaceCounts[effective] += 1;
			practices.push({
				id,
				slug: `${group.slug ?? "unassigned"}-${slugify(spec.name)}`,
				name: spec.name,
				groupSlug: group.slug ?? undefined,
				bindings: [mockPullRequestBinding],
				criteria: `## ${spec.name}\n\nWhat a review looks for.`,
				artifactKind: spec.artifactKind ?? "scm.pull_request",
				whyItMatters: spec.whyItMatters,
				deliveryBehavior: { summaryOnly: false },
				automatedReviewPolicy:
					spec.reviewable === false
						? {
								...mockPullRequestPolicy,
								automatedReview: {
									...mockPullRequestPolicy.automatedReview,
									mode: "NONE",
									evidenceSufficiency: "NONE",
								},
							}
						: mockPullRequestPolicy,
				automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
				displayOrder: practices.length,
				autonomy: assignment(held, effective, source),
				createdAt: new Date("2026-01-01"),
				updatedAt: new Date("2026-01-02"),
			});
			id += 1;
		}

		rollupGroups.push({
			groupSlug: group.slug ?? undefined,
			groupName: group.name ?? undefined,
			counts,
			overriddenCount,
			// The no-group bucket carries the workspace's answer; it is not a row that can hold one.
			autonomy:
				group.slug === null
					? assignment(workspaceDefault, effectiveDefault, "WORKSPACE")
					: assignment(group.override, groupEffective, group.override ? "GROUP" : "WORKSPACE"),
		});
	}

	return {
		settings: mockReviewSettings({
			defaultAutonomy: effectiveDefault,
			defaultAutonomyOverride: workspaceDefault,
		}),
		rollup: {
			counts: workspaceCounts,
			groups: rollupGroups,
			workspaceDefault: assignment(workspaceDefault, effectiveDefault, "WORKSPACE"),
		},
		practices,
	};
}

const slugify = (name: string) =>
	name
		.toLowerCase()
		.replaceAll(/[^a-z0-9]+/gu, "-")
		.replaceAll(/^-|-$/gu, "");

const SCALE_GROUP_NAMES = [
	"Pull request hygiene",
	"Testing",
	"Documentation",
	"Error handling",
	"Security",
	"Performance",
	"Accessibility",
	"API design",
	"Data modelling",
	"Observability",
	"Dependency care",
	"Release readiness",
	"Code review conduct",
	"Issue hygiene",
	"Incident response",
	"Configuration",
	"Migrations",
	"Front-end structure",
	"Back-end structure",
	"Concurrency",
	"Caching",
	"Build and CI",
	"Naming",
	"Refactoring",
	"Onboarding docs",
];

const SCALE_PRACTICE_NAMES = [
	"states the motivation",
	"links the issue it closes",
	"lists the steps a reviewer ran",
	"keeps the change reviewable in one sitting",
];

/** The hand-set groups, by their index in `SCALE_GROUP_NAMES`. */
const SCALE_GROUP_OVERRIDES: Partial<Record<number, PracticeAutonomy>> = {
	2: "OFF",
	7: "AUTOMATIC",
};

/** The hand-set practices, keyed `group index:practice index`. */
const SCALE_PRACTICE_OVERRIDES: Partial<Record<`${number}:${number}`, PracticeAutonomy>> = {
	"0:0": "AUTOMATIC",
	"4:1": "OFF",
};

/**
 * Deliberately lopsided: most of it inherits, a handful of groups and practices were changed by hand,
 * and one practice cannot be reviewed at all. A fixture where everything is set says nothing about
 * whether the inherited case recedes.
 */
export function scaleFixture(): AutonomyFixture {
	return buildAutonomyFixture({
		workspaceDefault: "HUMAN_APPROVAL",
		groups: SCALE_GROUP_NAMES.map((name, index) => ({
			slug: slugify(name),
			name,
			override: SCALE_GROUP_OVERRIDES[index],
			practices: SCALE_PRACTICE_NAMES.map((suffix, practiceIndex) => ({
				name: `${name}: ${suffix}`,
				override: SCALE_PRACTICE_OVERRIDES[`${index}:${practiceIndex}`],
				reviewable: !(index === 9 && practiceIndex === 3),
			})),
		})),
	});
}
