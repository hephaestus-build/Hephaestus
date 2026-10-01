import type {
	PracticeAutomatedReviewPolicy,
	PracticeDefinition,
	PracticeDefinitionOptions,
	PracticeEvidenceRequirement,
	PracticeWorkTypeDefinitionOptions,
} from "@/api/types.gen";
import { ARTIFACT_KIND, ARTIFACT_KIND_VALUES } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

export type PracticeReviewFields = Pick<
	PracticeDefinition,
	"signals" | "evidenceRequirements" | "onDrafts" | "subject" | "precondition"
>;

export type EvidenceStance = PracticeEvidenceRequirement["stance"];
export type EvidenceRole = "NOT_USED" | EvidenceStance;

export function artifactKindOfSignal(signal: string): string {
	const lastDot = signal.lastIndexOf(".");
	return lastDot === -1 ? signal : signal.slice(0, lastDot);
}

export function artifactKindOfSignals(signals: readonly string[]): string | undefined {
	const signal = signals[0];
	return hasText(signal) ? artifactKindOfSignal(signal) : undefined;
}

export const EMPTY_REVIEW_SETTINGS: PracticeReviewFields = {
	signals: [],
	evidenceRequirements: [],
	onDrafts: false,
	subject: "AUTHOR",
};

export function normalizeReviewSettings(reviewFields: PracticeReviewFields) {
	return {
		signals: [...new Set(reviewFields.signals)].sort((left, right) => left.localeCompare(right)),
		evidenceRequirements: [...reviewFields.evidenceRequirements].sort((left, right) =>
			left.sourceKind.localeCompare(right.sourceKind),
		),
		onDrafts: reviewFields.onDrafts,
		subject: reviewFields.subject,
		...(reviewFields.precondition ? { precondition: reviewFields.precondition } : {}),
	};
}

export function roleOf(
	evidenceRequirements: readonly PracticeEvidenceRequirement[],
	sourceKind: string,
): EvidenceRole {
	return evidenceRequirements.find((need) => need.sourceKind === sourceKind)?.stance ?? "NOT_USED";
}

export function withRole(
	evidenceRequirements: readonly PracticeEvidenceRequirement[],
	sourceKind: string,
	role: EvidenceRole,
): PracticeEvidenceRequirement[] {
	const remaining = evidenceRequirements.filter((need) => need.sourceKind !== sourceKind);
	if (role !== "NOT_USED") {
		remaining.push({ sourceKind, stance: role });
	}
	return remaining.sort((left, right) => left.sourceKind.localeCompare(right.sourceKind));
}

export function recommendedReviewSettings(
	options: PracticeWorkTypeDefinitionOptions,
): PracticeReviewFields {
	const recommended = options.signals.filter((option) => option.recommended);
	const signals = (recommended.length > 0 ? recommended : options.signals.slice(0, 1)).map(
		(option) => option.signal,
	);
	return normalizeReviewSettings({
		...EMPTY_REVIEW_SETTINGS,
		signals,
		evidenceRequirements: options.recommendedEvidenceRequirements,
	});
}

export function workTypeOptionsFor(
	definitionOptions: PracticeDefinitionOptions,
	artifactKind: string | undefined,
): PracticeWorkTypeDefinitionOptions | undefined {
	return definitionOptions.workTypes.find((option) => option.artifactKind === artifactKind);
}

export function orderedWorkTypes(
	definitionOptions: PracticeDefinitionOptions,
): PracticeWorkTypeDefinitionOptions[] {
	const rank = new Map(ARTIFACT_KIND_VALUES.map((kind, index) => [kind as string, index]));
	return [...definitionOptions.workTypes].sort(
		(left, right) =>
			(rank.get(left.artifactKind) ?? ARTIFACT_KIND_VALUES.length) -
			(rank.get(right.artifactKind) ?? ARTIFACT_KIND_VALUES.length),
	);
}

export function hasDrafts(artifactKind: string | undefined): boolean {
	return artifactKind === ARTIFACT_KIND.pullRequest;
}

export const OCCASION_ID_PREFIX = "practice-occasion";

export function occasionFieldId(field: string): string {
	return `${OCCASION_ID_PREFIX}-${field}`;
}

export interface ReviewSettingsProblem {
	message: string;
	focusId: string;
}

export function reviewSettingsProblem(
	reviewFields: PracticeReviewFields,
	policy: PracticeAutomatedReviewPolicy,
	options: PracticeWorkTypeDefinitionOptions | undefined,
): ReviewSettingsProblem | undefined {
	if (reviewFields.signals.length === 0) {
		return {
			message: "Choose when this practice is reviewed.",
			focusId: occasionFieldId("signals"),
		};
	}
	const declared = new Set(options?.signals.map((option) => option.signal));
	const noAutomatedReview = policy.automatedReview.mode === "NONE";
	for (const signal of reviewFields.signals) {
		if (declared.size > 0 && !declared.has(signal)) {
			return {
				message: "One of the chosen moments does not apply to this kind of work.",
				focusId: occasionFieldId("signals"),
			};
		}
	}
	if (noAutomatedReview && reviewFields.evidenceRequirements.length > 0) {
		return {
			message: "Guidance only cannot read any evidence.",
			focusId: occasionFieldId("evidence"),
		};
	}
	if (
		!noAutomatedReview &&
		!reviewFields.evidenceRequirements.some((need) => need.stance !== "CONTEXTUAL")
	) {
		return {
			message: "This review needs at least one source it cannot run without.",
			focusId: occasionFieldId("evidence"),
		};
	}
	const exhaustiveBlocked = reviewFields.evidenceRequirements.find(
		(need) =>
			need.stance === "EXHAUSTIVE" &&
			options?.allowedSources.some(
				(source) => source.sourceKind === need.sourceKind && !source.supportsExhaustiveEvidence,
			) === true,
	);
	if (exhaustiveBlocked) {
		return {
			message:
				"One source can never be captured whole, so nothing this review says about what is absent from it can rest on it.",
			focusId: occasionFieldId("evidence"),
		};
	}
	return undefined;
}
