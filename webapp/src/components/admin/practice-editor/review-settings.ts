import type {
	PracticeDefinition,
	PracticeDefinitionOptions,
	PracticeItemTypeOption,
	PracticeReadSource,
	ReadSourceOption,
	ReviewOn,
} from "@/api/types.gen";
import { itemKindRank } from "@/lib/item-kinds";

export type ReadNeed = PracticeReadSource["need"];
export type ReadChoice = ReadNeed | "NOT_USED";
export type ReviewedByKind = PracticeDefinition["reviewedBy"]["by"];

/** Canonical order prevents an untouched draft from comparing unequal to the saved definition. */
export function normalizeReviewOn(reviewOn: ReviewOn): ReviewOn {
	return {
		events: [...new Set(reviewOn.events)].sort((left, right) => left.localeCompare(right)),
		state: Object.fromEntries(
			Object.entries(reviewOn.state)
				.sort(([left], [right]) => left.localeCompare(right))
				.map(([key, values]) => [key, [...new Set(values)].sort()]),
		),
	};
}

export function recommendedReviewOn(itemType: PracticeItemTypeOption): ReviewOn {
	return normalizeReviewOn({
		events: itemType.events.filter((option) => option.recommended).map((option) => option.name),
		state: {},
	});
}

export function readNeedOf(sources: readonly PracticeReadSource[], source: string): ReadChoice {
	return sources.find((entry) => entry.source === source)?.need ?? "NOT_USED";
}

export function withReadNeed(
	sources: readonly PracticeReadSource[],
	source: string,
	choice: ReadChoice,
): PracticeReadSource[] {
	const remaining = sources.filter((entry) => entry.source !== source);
	if (choice !== "NOT_USED") {
		remaining.push({ source, need: choice });
	}
	return remaining.sort((left, right) => left.source.localeCompare(right.source));
}

export function itemTypeFor(
	definitionOptions: PracticeDefinitionOptions,
	kind: string | undefined,
): PracticeItemTypeOption | undefined {
	return definitionOptions.itemTypes.find((option) => option.kind === kind);
}

export function orderedItemTypes(
	definitionOptions: PracticeDefinitionOptions,
): PracticeItemTypeOption[] {
	return [...definitionOptions.itemTypes].sort(
		(left, right) => itemKindRank(left.kind) - itemKindRank(right.kind),
	);
}

export const REVIEW_ON_ID_PREFIX = "practice-review-on";

export function reviewOnFieldId(field: "events" | "state" | "reads"): string {
	return `${REVIEW_ON_ID_PREFIX}-${field}`;
}

/**
 * Why a set of read sources cannot be saved for an automated review, or undefined when it can.
 */
export function readsProblem(
	sources: readonly PracticeReadSource[],
	offered: readonly ReadSourceOption[],
): string | undefined {
	for (const entry of sources) {
		const option = offered.find((candidate) => candidate.source === entry.source);
		if (!option) {
			return "One chosen source is not offered for this kind of work. Turn it off to continue.";
		}
		if (!option.needs.includes(entry.need)) {
			return `${option.label} cannot be captured whole. Change it to Required or Optional.`;
		}
	}
	return sources.some((entry) => entry.need !== "OPTIONAL")
		? undefined
		: "Set at least one source to Required. A review cannot run without one.";
}

export interface ReviewSettingsProblem {
	message: string;
	focusId: string;
}

/**
 * The first reason Review on or the reads cannot be saved for this item type. Reads are checked only
 * while Hephaestus reviews the practice: any other choice saves none.
 */
export function reviewSettingsProblem(
	reviewOn: ReviewOn,
	reads: readonly PracticeReadSource[],
	by: ReviewedByKind,
	itemType: PracticeItemTypeOption,
): ReviewSettingsProblem | undefined {
	const offered = new Set(itemType.events.map((option) => option.name));
	if (reviewOn.events.some((name) => !offered.has(name))) {
		return {
			message:
				"One of the chosen events is not offered for this kind of work. Clear it to continue.",
			focusId: reviewOnFieldId("events"),
		};
	}
	for (const [name, values] of Object.entries(reviewOn.state)) {
		const dimension = itemType.reviewOnStateDimensions.find((option) => option.name === name);
		if (
			!dimension ||
			values.length === 0 ||
			values.some((value) => !dimension.values.some((option) => option.value === value))
		) {
			return {
				message: "Choose states this kind of work offers.",
				focusId:
					itemType.reviewOnStateDimensions.length > 0
						? reviewOnFieldId("state")
						: reviewOnFieldId("events"),
			};
		}
	}
	const message = by === "HEPHAESTUS" ? readsProblem(reads, itemType.readSources) : undefined;
	return message === undefined ? undefined : { message, focusId: reviewOnFieldId("reads") };
}
