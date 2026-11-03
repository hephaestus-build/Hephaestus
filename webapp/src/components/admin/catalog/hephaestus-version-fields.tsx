import type { ReactNode } from "react";

import type {
	GroupDefinition,
	PracticeDefinition,
	PracticeDefinitionOptions,
} from "@/api/types.gen";
import {
	PracticeGuideVersion,
	PracticeVisualVersion,
} from "@/components/admin/practice-editor/practice-guidance-version";
import { aboutLabel } from "@/components/practice-vocabulary/about-role-labels";
import { humanizeToken } from "@/lib/humanize";
import { itemKindNoun } from "@/lib/item-kinds";

export interface VersionField {
	label: string;
	value: ReactNode;
	mono?: boolean;
}

const NEED_TEXT = {
	REQUIRED: "required",
	COMPLETE: "required and captured whole",
	OPTIONAL: "optional",
} as const satisfies Record<PracticeDefinition["reads"]["sources"][number]["need"], string>;

const REVIEWED_BY_TEXT = {
	HEPHAESTUS: "Hephaestus",
	PEOPLE: "People",
	NOBODY: "Nobody",
} as const satisfies Record<PracticeDefinition["reviewedBy"]["by"], string>;

function textOrNotSet(value: string | null): string {
	return value === null || value === "" ? "Not set" : value;
}

export function groupVersionFields(shipped: GroupDefinition): VersionField[] {
	return [
		{ label: "Name", value: shipped.name },
		{ label: "Description", value: textOrNotSet(shipped.description) },
		{ label: "Icon", value: shipped.icon === null ? "Not set" : humanizeToken(shipped.icon) },
		{ label: "Color", value: shipped.color === null ? "Not set" : humanizeToken(shipped.color) },
	];
}

export function practiceVersionFields(
	shipped: PracticeDefinition,
	itemKind: string,
	definitionOptions: PracticeDefinitionOptions,
): VersionField[] {
	const itemType = definitionOptions.itemTypes.find((option) => option.kind === itemKind);
	const eventLabel = (name: string) =>
		itemType?.events.find((event) => event.name === name)?.label ?? "Other event";
	const sourceLabel = (source: string) =>
		itemType?.readSources.find((option) => option.source === source)?.label ?? "Other source";
	const stateLines = Object.entries(shipped.reviewOn.state).map(([name, values]) => {
		const dimension = itemType?.reviewOnStateDimensions.find((option) => option.name === name);
		const valueLabels = values.map(
			(value) => dimension?.values.find((option) => option.value === value)?.label ?? value,
		);
		return `${dimension?.label ?? humanizeToken(name)}: ${valueLabels.join(", ")}`;
	});
	const { appliesWhen, scan, reviewedBy, guidance } = shipped;
	return [
		{ label: "Name", value: shipped.name },
		{
			label: "Review on",
			value:
				shipped.reviewOn.events.length === 0 && stateLines.length === 0
					? "Only when someone asks for a review"
					: [...shipped.reviewOn.events.map(eventLabel), ...stateLines].join("\n"),
		},
		{
			label: "Applies when",
			value:
				appliesWhen === null
					? "Always"
					: [
							...appliesWhen.anyOf.map((clause) => {
								if (clause.changedPath !== undefined) {
									return `Changed path matches ${clause.changedPath.join(", ")}`;
								}
								if (clause.diffContains !== undefined) {
									return `Change contains ${clause.diffContains.join(", ")}`;
								}
								if (clause.merged === true) {
									return "The work has merged";
								}
								return `Has related ${itemKindNoun(clause.hasItems, 2)}`;
							}),
							`Otherwise: ${appliesWhen.otherwise}`,
						].join("\n"),
		},
		{ label: "About", value: aboutLabel(shipped.about) },
		{
			label: "Reads",
			value:
				shipped.reads.sources.length === 0
					? "Nothing"
					: shipped.reads.sources
							.map(({ source, need }) => `${sourceLabel(source)} · ${NEED_TEXT[need]}`)
							.join("\n"),
		},
		{
			label: "Reviewed by",
			value:
				reviewedBy.humanReason === null
					? REVIEWED_BY_TEXT[reviewedBy.by]
					: `${REVIEWED_BY_TEXT[reviewedBy.by]}: ${reviewedBy.humanReason.description}`,
		},
		{ label: "Criteria", value: shipped.criteria },
		{ label: "Script", value: shipped.script ?? "Built-in", mono: shipped.script !== null },
		{
			label: "Scan",
			value:
				scan === null
					? "Not included in scans"
					: [
							`Include: ${scan.include.join(", ")}`,
							scan.exclude.length > 0 ? `Exclude: ${scan.exclude.join(", ")}` : undefined,
						]
							.filter((line) => line !== undefined)
							.join("\n"),
		},
		{
			label: "Line notes",
			value: shipped.lineNotes
				? "Feedback can appear on a changed line."
				: "Feedback stays in the summary, not on a changed line.",
		},
		{ label: "Why it matters", value: textOrNotSet(guidance.whyItMatters) },
		{ label: "What good looks like", value: textOrNotSet(guidance.whatGoodLooksLike) },
		{ label: "Going well", value: textOrNotSet(guidance.goingWell) },
		{
			label: "Visual",
			value:
				guidance.visual === null ? "Not set" : <PracticeVisualVersion visual={guidance.visual} />,
		},
		{
			label: "Guide",
			value: guidance.guide === null ? "Not set" : <PracticeGuideVersion guide={guidance.guide} />,
		},
	];
}
