import { zCreatePracticeGroupRequest, zCreatePracticeRequest } from "@/api/zod.gen";

import type {
	PracticeDefinitionInput,
	PracticeDefinitionOptions,
	PracticeGuide,
	PracticeItemTypeOption,
	PracticeVisual,
	ScanDefinition,
} from "@/api/types.gen";
import type { FormError } from "@/components/common/FormErrorSummary";
import type { AboutRole } from "@/components/practice-vocabulary/about-role-labels";
import { hasText } from "@/lib/text";

import { type AppliesWhenDraft, appliesWhenDraftOf, parseAppliesWhen } from "./applies-when";
import {
	FIGURE_PLACEHOLDER,
	hasFigurePlaceholder,
	hasGuide,
	hasVisual,
	MAX_SVG_SIZE,
	NO_GUIDE,
	NO_VISUAL,
	svgProblem,
} from "./practice-guidance-draft";
import type {
	PracticeDefinitionSubmission,
	PracticeDefinitionValue,
} from "./PracticeDefinitionForm";
import type { ReviewSettings } from "./PracticeReviewSettingsEditor";
import {
	itemTypeFor,
	normalizeReviewOn,
	recommendedReviewOn,
	type ReviewSettingsProblem,
	reviewSettingsProblem,
} from "./review-settings";
import {
	type ReviewedByDraft,
	reviewedByDraftOf,
	reviewedByOf,
	type ReviewedByProblem,
	reviewedByProblem,
} from "./reviewed-by";

// Base UI Select requires a string value for an unassigned group.
export const NO_GROUP = "__none__";

export const MAX_SCRIPT_LENGTH = 100_000;
export const MAX_GUIDANCE_LENGTH = 2000;

export interface PracticeDefinitionDraft {
	slug: string;
	kind: string;
	groupSlug: string;
	name: string;
	settings: ReviewSettings;
	appliesWhen: AppliesWhenDraft;
	about: AboutRole | null;
	reviewedBy: ReviewedByDraft;
	criteria: string;
	script: string;
	/** Kept unchanged because this form does not edit scan settings. */
	scan: ScanDefinition | null;
	lineNotes: boolean;
	whyItMatters: string;
	whatGoodLooksLike: string;
	goingWell: string;
	visual: PracticeVisual;
	guide: PracticeGuide;
}

/** What one kind of item owns, stashed so switching kind and back does not discard the work. */
export type KindDraft = Pick<
	PracticeDefinitionDraft,
	"settings" | "appliesWhen" | "about" | "script" | "scan"
>;

export function kindDraftOf(draft: PracticeDefinitionDraft): KindDraft {
	return {
		settings: draft.settings,
		appliesWhen: draft.appliesWhen,
		about: draft.about,
		script: draft.script,
		scan: draft.scan,
	};
}

export function freshKindDraft(itemType: PracticeItemTypeOption | undefined): KindDraft {
	return {
		settings: {
			reviewOn: itemType ? recommendedReviewOn(itemType) : { events: [], state: {} },
			reads: [],
		},
		appliesWhen: { clauses: [], otherwise: "" },
		about: itemType?.aboutRoles[0] ?? null,
		script: "",
		scan: null,
	};
}

export function blankDraft(itemType: PracticeItemTypeOption | undefined): PracticeDefinitionDraft {
	return {
		slug: "",
		kind: itemType?.kind ?? "",
		groupSlug: NO_GROUP,
		name: "",
		...freshKindDraft(itemType),
		reviewedBy: reviewedByDraftOf({ by: "HEPHAESTUS", humanReason: null, limits: [] }),
		criteria: "",
		lineNotes: true,
		whyItMatters: "",
		whatGoodLooksLike: "",
		goingWell: "",
		visual: NO_VISUAL,
		guide: NO_GUIDE,
	};
}

export function draftOf(value: PracticeDefinitionValue): PracticeDefinitionDraft {
	const { definition } = value;
	const { guidance } = definition;
	return {
		slug: value.slug,
		kind: value.kind,
		groupSlug: value.groupSlug ?? NO_GROUP,
		name: definition.name,
		settings: {
			reviewOn: normalizeReviewOn(definition.reviewOn),
			reads: definition.reads.sources,
		},
		appliesWhen: appliesWhenDraftOf(definition.appliesWhen),
		about: definition.about,
		reviewedBy: reviewedByDraftOf(definition.reviewedBy),
		criteria: definition.criteria,
		script: definition.script ?? "",
		scan: definition.scan,
		lineNotes: definition.lineNotes,
		whyItMatters: guidance.whyItMatters ?? "",
		whatGoodLooksLike: guidance.whatGoodLooksLike ?? "",
		goingWell: guidance.goingWell ?? "",
		visual: guidance.visual ?? NO_VISUAL,
		guide: guidance.guide ?? NO_GUIDE,
	};
}

/** Draft fields survive reviewer changes; saving decides which fields to keep. */
export function clearedBySave(draft: PracticeDefinitionDraft): string[] {
	if (draft.reviewedBy.by === "HEPHAESTUS") {
		return [];
	}
	return [
		draft.settings.reads.length > 0 && "the read sources",
		hasText(draft.script.trim()) && "the practice script",
		draft.scan !== null && "the scan settings",
		draft.reviewedBy.limits.length > 0 && "the review limits",
	].filter((phrase): phrase is string => phrase !== false);
}

function textOrNull(text: string): string | null {
	const trimmed = text.trim();
	return hasText(trimmed) ? trimmed : null;
}

/** Only Hephaestus reviews retain reads, scripts, and scans; a blank script selects the built-in review. */
export function definitionOfDraft(
	draft: PracticeDefinitionDraft,
	itemType: PracticeItemTypeOption | undefined,
	sourceContract: string,
): PracticeDefinitionSubmission {
	const automated = draft.reviewedBy.by === "HEPHAESTUS";
	const definition: PracticeDefinitionInput = {
		name: draft.name.trim(),
		reviewOn: normalizeReviewOn(draft.settings.reviewOn),
		appliesWhen: parseAppliesWhen(draft.appliesWhen, itemType).value,
		about: draft.about,
		reads: { contract: sourceContract, sources: automated ? draft.settings.reads : [] },
		reviewedBy: reviewedByOf(draft.reviewedBy),
		criteria: draft.criteria.trim(),
		script: automated ? textOrNull(draft.script) : null,
		scan: automated ? draft.scan : null,
		lineNotes: draft.lineNotes,
		guidance: {
			whyItMatters: textOrNull(draft.whyItMatters),
			whatGoodLooksLike: textOrNull(draft.whatGoodLooksLike),
			goingWell: textOrNull(draft.goingWell),
			// Preserve loaded markup exactly; trimming would change it during an unrelated save.
			visual: hasVisual(draft.visual)
				? { svg: draft.visual.svg, alt: draft.visual.alt.trim() }
				: null,
			guide: hasGuide(draft.guide) ? draft.guide : null,
		},
	};
	return {
		slug: draft.slug,
		kind: draft.kind,
		groupSlug: draft.groupSlug === NO_GROUP ? null : draft.groupSlug,
		definition,
	};
}

export const FIELD_IDS = {
	name: "practice-name",
	slug: "practice-slug",
	criteria: "practice-criteria",
	about: "practice-about",
	appliesWhen: "practice-applies-when",
	otherwise: "practice-otherwise",
	script: "practice-script",
	visualSvg: "practice-visual-svg",
	visualAlt: "practice-visual-alt",
	guide: "practice-guide-markdown",
} as const;

export interface DraftErrors {
	byField: Partial<Record<string, string>>;
	reviewedBy?: ReviewedByProblem;
	settings?: ReviewSettingsProblem;
	/**
	 * One list, in the order the fields appear, so the summary reads down the form and the first
	 * entry is also the field to focus.
	 */
	summary: FormError[];
}

export interface DraftErrorReveals {
	revealTechnical: () => void;
	revealGuide: () => void;
	revealScript: () => void;
}

function visualSvgError(visual: PracticeVisual): string | undefined {
	if (!hasVisual(visual)) {
		return undefined;
	}
	switch (svgProblem(visual.svg)) {
		case "not-svg": {
			return "Enter SVG markup with an <svg> root element.";
		}
		case "too-large": {
			return `Use an SVG of ${MAX_SVG_SIZE} or less. Simplify the drawing.`;
		}
		case undefined: {
			return undefined;
		}
	}
}

function guideError(guide: PracticeGuide): string | undefined {
	if (!hasGuide(guide)) {
		return undefined;
	}
	if (!hasText(guide.markdown.trim())) {
		return "Write the guide, or remove it.";
	}
	return hasFigurePlaceholder(guide.markdown)
		? `Replace “${FIGURE_PLACEHOLDER}” with what the figure shows.`
		: undefined;
}

export function draftErrors(
	draft: PracticeDefinitionDraft,
	mode: "create" | "edit",
	definitionOptions: PracticeDefinitionOptions,
	reveals: DraftErrorReveals,
): DraftErrors {
	const itemType = itemTypeFor(definitionOptions, draft.kind);
	const automated = draft.reviewedBy.by === "HEPHAESTUS";
	const name = draft.name.trim();
	const appliesWhen = parseAppliesWhen(draft.appliesWhen, itemType);
	const reviewedBy = reviewedByProblem(draft.reviewedBy);
	const settings = itemType
		? reviewSettingsProblem(
				draft.settings.reviewOn,
				draft.settings.reads,
				draft.reviewedBy.by,
				itemType,
			)
		: undefined;
	const visualAlt =
		hasVisual(draft.visual) && !hasText(draft.visual.alt.trim())
			? "Describe what the visual shows."
			: undefined;
	const entries: (FormError | false | undefined)[] = [
		(name.length < 3 || name.length > 128) && {
			fieldId: FIELD_IDS.name,
			message: "Enter a name of 3 to 128 characters.",
		},
		!hasText(draft.criteria.trim()) && {
			fieldId: FIELD_IDS.criteria,
			message: "Enter what to look for.",
		},
		hasText(visualSvgError(draft.visual)) && {
			fieldId: FIELD_IDS.visualSvg,
			message: visualSvgError(draft.visual) ?? "",
		},
		hasText(visualAlt) && { fieldId: FIELD_IDS.visualAlt, message: visualAlt },
		hasText(guideError(draft.guide)) && {
			fieldId: FIELD_IDS.guide,
			message: guideError(draft.guide) ?? "",
			reveal: reveals.revealGuide,
		},
		reviewedBy && { fieldId: reviewedBy.focusId, message: reviewedBy.message },
		settings && { fieldId: settings.focusId, message: settings.message },
		draft.about !== null &&
			itemType !== undefined &&
			!itemType.aboutRoles.includes(draft.about) && {
				fieldId: FIELD_IDS.about,
				message: "Choose a role this kind of work has, or The work.",
			},
		hasText(appliesWhen.clausesError) && {
			fieldId: FIELD_IDS.appliesWhen,
			message: appliesWhen.clausesError,
		},
		hasText(appliesWhen.otherwiseError) && {
			fieldId: FIELD_IDS.otherwise,
			message: appliesWhen.otherwiseError,
		},
		automated &&
			draft.script.length > MAX_SCRIPT_LENGTH && {
				fieldId: FIELD_IDS.script,
				message: "Shorten the practice script to at most 100,000 characters.",
				reveal: reveals.revealScript,
			},
		mode === "create" &&
			!zCreatePracticeRequest.shape.slug.safeParse(draft.slug).success && {
				fieldId: FIELD_IDS.slug,
				message: zCreatePracticeGroupRequest.shape.slug.safeParse(draft.slug).success
					? "This identifier is not available. Choose a different one."
					: "Enter an identifier of 1–64 lowercase letters, numbers and single hyphens.",
				reveal: reveals.revealTechnical,
			},
	];
	const summary = entries.filter((entry): entry is FormError => Boolean(entry));
	return {
		byField: Object.fromEntries(summary.map((entry) => [entry.fieldId, entry.message])),
		reviewedBy,
		settings,
		summary,
	};
}
