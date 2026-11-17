import deepEqual from "fast-deep-equal";

import type {
	AppliesWhen,
	PracticeDefinition,
	PracticeReadSource,
	PracticeReleaseField,
	PracticeVersion,
	ReviewedBy,
	ScanDefinition,
} from "@/api/types.gen";
import { aboutLabel } from "@/components/practice-vocabulary/about-role-labels";
import { hasText } from "@/lib/text";

/** A current version must contain its definition; absence is a server contract error. */
export function currentDefinition(practice: {
	slug: string;
	currentVersion: Pick<PracticeVersion, "definition">;
}): PracticeDefinition {
	const { definition } = practice.currentVersion;
	if (definition === null) {
		throw new Error(`The current version of practice ${practice.slug} has no definition.`);
	}
	return definition;
}

/** A part of a practice's definition that can change on its own. */
export type PracticeDefinitionField = PracticeReleaseField["field"];

export const PRACTICE_DEFINITION_FIELDS = {
	NAME: { label: "Name", key: "name" },
	REVIEW_ON: { label: "Review on", key: "reviewOn" },
	APPLIES_WHEN: { label: "Applies when", key: "appliesWhen" },
	ABOUT: { label: "About", key: "about" },
	READS: { label: "Reads", key: "reads" },
	REVIEWED_BY: { label: "Reviewed by", key: "reviewedBy" },
	CRITERIA: { label: "Review criteria", key: "criteria" },
	SCRIPT: { label: "Practice script", key: "script" },
	SCAN: { label: "Scans", key: "scan" },
	LINE_NOTES: { label: "Line notes", key: "lineNotes" },
	GUIDANCE: { label: "Guidance", key: "guidance" },
} satisfies Record<PracticeDefinitionField, { label: string; key: keyof PracticeDefinition }>;

const FIELD_ORDER = [
	"NAME",
	"REVIEW_ON",
	"APPLIES_WHEN",
	"ABOUT",
	"READS",
	"REVIEWED_BY",
	"CRITERIA",
	"SCRIPT",
	"SCAN",
	"LINE_NOTES",
	"GUIDANCE",
] as const satisfies readonly PracticeDefinitionField[];

export function definitionDifferences(
	current: PracticeDefinition,
	offered: PracticeDefinition,
): PracticeDefinitionField[] {
	return FIELD_ORDER.filter((field) => {
		const { key } = PRACTICE_DEFINITION_FIELDS[field];
		return !deepEqual(current[key], offered[key]);
	});
}

export const REVIEWED_BY_LABELS: Record<ReviewedBy["by"], string> = {
	HEPHAESTUS: "Hephaestus",
	PEOPLE: "People",
	NOBODY: "Nobody",
};

export const READ_NEED_LABELS: Record<PracticeReadSource["need"], string> = {
	REQUIRED: "required",
	COMPLETE: "required in full",
	OPTIONAL: "optional",
};

const NOBODY_SENTENCE = "This practice offers guidance without automated review.";

export function reviewedByLines(reviewedBy: ReviewedBy): string[] {
	const reason = reviewedBy.humanReason;
	return [
		REVIEWED_BY_LABELS[reviewedBy.by],
		...(reviewedBy.by === "NOBODY" ? [NOBODY_SENTENCE] : []),
		...(reason === null ? [] : [reason.description]),
		// A practice keeps its limits under every reviewer, but they describe what Hephaestus cannot judge.
		...(reviewedBy.by === "HEPHAESTUS" ? reviewedBy.limits.map((limit) => limit.description) : []),
	];
}

export function appliesWhenLines(appliesWhen: AppliesWhen | null): string[] {
	if (appliesWhen === null) {
		return ["Always"];
	}
	return [
		...appliesWhen.anyOf.flatMap((clause) => [
			...(clause.changedPath ? [`Changes a path matching ${clause.changedPath.join(", ")}`] : []),
			...(clause.diffContains ? [`The change contains ${clause.diffContains.join(", ")}`] : []),
			...(hasText(clause.hasItems) ? [`Has ${clause.hasItems}`] : []),
		]),
		`Otherwise: ${appliesWhen.otherwise}`,
	];
}

export function scanLines(scan: ScanDefinition | null): string[] {
	if (scan === null) {
		return ["Not included in scans"];
	}
	return [
		`Include: ${scan.include.join(", ")}`,
		...(scan.exclude.length > 0 ? [`Exclude: ${scan.exclude.join(", ")}`] : []),
		...(scan.context.length > 0 ? [`Context: ${scan.context.join(", ")}`] : []),
	];
}

export function definitionFieldText(
	definition: PracticeDefinition,
	field: PracticeDefinitionField,
): string {
	switch (field) {
		case "NAME": {
			return definition.name;
		}
		case "CRITERIA": {
			return definition.criteria;
		}
		case "SCRIPT": {
			return definition.script ?? "Built-in";
		}
		case "ABOUT": {
			return aboutLabel(definition.about);
		}
		case "LINE_NOTES": {
			return definition.lineNotes ? "Leaves line notes" : "Summary only";
		}
		case "REVIEWED_BY": {
			return reviewedByLines(definition.reviewedBy).join("\n");
		}
		case "APPLIES_WHEN": {
			return appliesWhenLines(definition.appliesWhen).join("\n");
		}
		case "SCAN": {
			return scanLines(definition.scan).join("\n");
		}
		case "READS": {
			return definition.reads.sources
				.map(({ source, need }) => `${source} (${READ_NEED_LABELS[need]})`)
				.join("\n");
		}
		case "REVIEW_ON": {
			return [
				...definition.reviewOn.events,
				...Object.entries(definition.reviewOn.state).map(
					([dimension, values]) => `${dimension}: ${values.join(", ")}`,
				),
			].join("\n");
		}
		case "GUIDANCE": {
			const { whyItMatters, whatGoodLooksLike, goingWell } = definition.guidance;
			return [
				`Why it matters: ${whyItMatters ?? "Not set"}`,
				`What good looks like: ${whatGoodLooksLike ?? "Not set"}`,
				`Going well: ${goingWell ?? "Not set"}`,
			].join("\n");
		}
	}
}
