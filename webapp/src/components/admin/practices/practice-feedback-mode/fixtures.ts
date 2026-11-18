import type {
	FeedbackModeAssignment,
	FeedbackModeRollup,
	Practice,
	PracticeDefinition,
	PracticeGroup,
	PracticeReviewSettings,
} from "@/api/types.gen";
import { reviewSettings } from "@/components/admin/practices/review/fixtures";
import type { FeedbackMode } from "@/lib/practice-feedback-mode";
import { mockPullRequestReviewedBy, mockPullRequestReviewFields } from "@/mocks/fixtures/practice";

export interface PracticeSpec {
	name: string;
	override?: FeedbackMode;
	reviewedBy?: PracticeDefinition["reviewedBy"]["by"];
	whyItMatters?: string;
	kind?: string;
}

export interface GroupSpec {
	slug: string | null;
	name: string | null;
	override?: FeedbackMode;
	practices: PracticeSpec[];
}

export interface FeedbackModeFixture {
	settings: PracticeReviewSettings;
	rollup: FeedbackModeRollup;
	groups: PracticeGroup[];
	practices: Practice[];
}

function assignment(
	override: FeedbackMode | undefined,
	effective: FeedbackMode,
	source: FeedbackModeAssignment["source"],
): FeedbackModeAssignment {
	return { effective, override: override ?? null, source };
}

const CATALOG_ORIGIN = {
	catalogSlug: null,
	localEdits: [],
	offeredDigest: null,
	state: "YOURS",
} satisfies Practice["catalogOrigin"];

function reviewedByOf(
	by: PracticeDefinition["reviewedBy"]["by"],
): PracticeDefinition["reviewedBy"] {
	if (by === "PEOPLE") {
		return {
			by,
			humanReason: {
				code: "NEEDS_TEAM_CONTEXT",
				description: "Only the team knows which incidents this alert should page for.",
			},
			limits: [],
		};
	}
	return by === "NOBODY" ? { by, humanReason: null, limits: [] } : mockPullRequestReviewedBy;
}

function practiceOf(spec: PracticeSpec, slug: string, groupSlug: string | null, position: number) {
	const definition: PracticeDefinition = {
		name: spec.name,
		...mockPullRequestReviewFields,
		reviewedBy: reviewedByOf(spec.reviewedBy ?? "HEPHAESTUS"),
		criteria: `## ${spec.name}\n\nWhat a review looks for.`,
		script: null,
		scan: null,
		lineNotes: true,
		guidance: {
			whyItMatters: spec.whyItMatters ?? null,
			whatGoodLooksLike: null,
			goingWell: null,
			visual: null,
			guide: null,
		},
	};
	return {
		id: String(position + 1),
		slug,
		kind: spec.kind ?? "scm.pull_request",
		groupSlug,
		position,
		catalogOrigin: CATALOG_ORIGIN,
		editedIn: "HEPHAESTUS" as const,
		createdAt: new Date("2026-01-01T00:00:00Z"),
		updatedAt: new Date("2026-01-02T00:00:00Z"),
		currentVersion: {
			id: `version-${position + 1}`,
			number: 1,
			name: spec.name,
			definition,
			reviewDigest: `digest-${position + 1}`,
			createdAt: new Date("2026-01-01T00:00:00Z"),
			createdBy: null,
			changed: [],
			reviewChanged: false,
			sourceCommit: null,
		},
	};
}

export function buildFeedbackModeFixture({
	workspaceDefault,
	groups,
}: {
	/** Absent means this workspace has never chosen, so Approve first applies. */
	workspaceDefault?: FeedbackMode;
	groups: GroupSpec[];
}): FeedbackModeFixture {
	const effectiveDefault: FeedbackMode = workspaceDefault ?? "APPROVE_FIRST";
	const practices: Practice[] = [];
	const practiceGroups: PracticeGroup[] = [];
	const counts: Record<FeedbackMode, number> = { OFF: 0, APPROVE_FIRST: 0, AUTOMATIC: 0 };

	for (const group of groups) {
		const groupEffective = group.override ?? effectiveDefault;
		if (group.slug !== null) {
			practiceGroups.push({
				id: `group-${practiceGroups.length + 1}`,
				slug: group.slug,
				name: group.name ?? group.slug,
				description: null,
				icon: null,
				color: null,
				position: practiceGroups.length,
				shownToDevelopers: true,
				catalogOrigin: CATALOG_ORIGIN,
				feedbackMode: assignment(
					group.override,
					groupEffective,
					group.override ? "GROUP" : "WORKSPACE",
				),
				createdAt: new Date("2026-01-01T00:00:00Z"),
				updatedAt: null,
			});
		}
		for (const spec of group.practices) {
			const effective = spec.override ?? groupEffective;
			let source: FeedbackModeAssignment["source"] = group.override ? "GROUP" : "WORKSPACE";
			if (spec.override) {
				source = "PRACTICE";
			}
			counts[effective] += 1;
			practices.push({
				...practiceOf(
					spec,
					`${group.slug ?? "unassigned"}-${slugify(spec.name)}`,
					group.slug,
					practices.length,
				),
				feedbackMode: assignment(spec.override, effective, source),
			});
		}
	}

	return {
		settings: reviewSettings({
			defaultFeedbackMode: effectiveDefault,
			defaultFeedbackModeOverride: workspaceDefault ?? null,
		}),
		rollup: {
			off: counts.OFF,
			approveFirst: counts.APPROVE_FIRST,
			automatic: counts.AUTOMATIC,
			defaultMode: effectiveDefault,
		},
		groups: practiceGroups,
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

const SCALE_GROUP_OVERRIDES: Partial<Record<number, FeedbackMode>> = {
	2: "OFF",
	7: "AUTOMATIC",
};

const SCALE_PRACTICE_OVERRIDES: Partial<Record<`${number}:${number}`, FeedbackMode>> = {
	"0:0": "AUTOMATIC",
	"4:1": "OFF",
};

export function scaleFixture(): FeedbackModeFixture {
	return buildFeedbackModeFixture({
		workspaceDefault: "APPROVE_FIRST",
		groups: SCALE_GROUP_NAMES.map((name, index) => ({
			slug: slugify(name),
			name,
			override: SCALE_GROUP_OVERRIDES[index],
			practices: SCALE_PRACTICE_NAMES.map((suffix, practiceIndex) => ({
				name: `${name}: ${suffix}`,
				override: SCALE_PRACTICE_OVERRIDES[`${index}:${practiceIndex}`],
				reviewedBy: index === 9 && practiceIndex === 3 ? "PEOPLE" : undefined,
			})),
		})),
	});
}
