import { HandIcon } from "lucide-react";

import type { PracticeEvidenceOutcome, PracticeWorkTypeDefinitionOptions } from "@/api/types.gen";
import { OccasionLifecycle } from "@/components/admin/practice-editor/OccasionLifecycle";
import { PracticeEvidenceEditor } from "@/components/admin/practice-editor/PracticeEvidenceEditor";
import { PracticeEvidenceOutcomeSummary } from "@/components/admin/practice-editor/PracticeEvidenceOutcomeSummary";
import {
	type PracticeReviewFields,
	normalizeReviewSettings,
	OCCASION_ID_PREFIX,
	occasionFieldId,
} from "@/components/admin/practice-editor/review-settings";
import { FieldError } from "@/components/ui/field";
import { artifactKindIcon, artifactKindLabel } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

/** Also the `aria-describedby` target: focus lands on the control, not on the rendered message. */
const REVIEW_SETTINGS_ERROR_ID = "practice-review-settings-error";

/** Human review also covers policies unavailable on this instance: both record evidence without AI review. */
export type PracticeOccasionMode = "reviewed" | "human-review" | "guidance-only";

export interface PracticeReviewSettingsEditorProps {
	options: PracticeWorkTypeDefinitionOptions;
	/**
	 * The one occasion this practice is reviewed on. A practice has exactly one: to read different
	 * evidence at a different moment, the server asks for a second practice rather than a second
	 * occasion, so there is nothing here to add to or remove.
	 */
	reviewFields: PracticeReviewFields;
	onChange: (reviewFields: PracticeReviewFields) => void;
	mode?: PracticeOccasionMode;
	outcome?: PracticeEvidenceOutcome;
	error?: string;
	/** The control the error points at, so the field it names is the one that opens. */
	errorFocusId?: string;
	disabled?: boolean;
}

/**
 * When a practice is reviewed and what that review reads: a moment strip, the draft question, and
 * one evidence list. Deliberately not a card — it is the body of the form's own section, and a
 * border around it would say there is a second one of these somewhere.
 */
export function PracticeReviewSettingsEditor({
	options,
	reviewFields,
	onChange,
	mode = "reviewed",
	outcome,
	error,
	errorFocusId,
	disabled = false,
}: PracticeReviewSettingsEditorProps) {
	const WorkIcon = artifactKindIcon(options.artifactKind);
	const signalsInvalid = errorFocusId === occasionFieldId("signals");
	const reviewWhenInvalid = errorFocusId === occasionFieldId("reviewWhen");
	const evidenceInvalid = errorFocusId === occasionFieldId("evidence");
	// A review asked for by hand runs a practice the workspace lets run: guidance-only and
	// human-review-needed practices sit at OFF, so promising one here would be a promise nothing keeps.
	const handAsked = options.manualReviewSignal !== undefined && mode === "reviewed";
	const toggleSignal = (signal: string, chosen: boolean) =>
		onChange(
			normalizeReviewSettings({
				...reviewFields,
				signals: chosen
					? [...reviewFields.signals, signal]
					: reviewFields.signals.filter((value) => value !== signal),
			}),
		);

	return (
		<div className="space-y-4">
			<div className="space-y-1.5">
				<p className="flex items-center gap-2 text-sm font-medium">
					<WorkIcon className="size-4 text-muted-foreground" aria-hidden />
					{artifactKindLabel(options.artifactKind)}
				</p>
				{mode !== "guidance-only" && (
					<p className="text-sm text-muted-foreground">
						{mode === "reviewed"
							? "Every review is checked for the evidence it must have before it runs. Missing or incomplete evidence skips the practice rather than guessing."
							: "Evidence is recorded, but nothing is reviewed while the practice asks for a human."}
					</p>
				)}
			</div>

			{outcome && (
				<PracticeEvidenceOutcomeSummary outcome={outcome} sources={options.allowedSources} />
			)}

			<OccasionLifecycle
				workType={options}
				selected={reviewFields.signals}
				onToggle={toggleSignal}
				reviewWhen={reviewFields.reviewWhen}
				onReviewWhenChange={(reviewWhen) =>
					onChange(normalizeReviewSettings({ ...reviewFields, reviewWhen }))
				}
				errorId={signalsInvalid && hasText(error) ? REVIEW_SETTINGS_ERROR_ID : undefined}
				reviewWhenErrorId={
					reviewWhenInvalid && hasText(error) ? REVIEW_SETTINGS_ERROR_ID : undefined
				}
				disabled={disabled}
			/>

			{mode === "guidance-only" ? (
				<p className="text-sm text-muted-foreground">
					Guidance only: this practice reads nothing, because no review runs.
				</p>
			) : (
				<PracticeEvidenceEditor
					options={options}
					evidenceRequirements={reviewFields.evidenceRequirements}
					idPrefix={OCCASION_ID_PREFIX}
					disabled={disabled}
					invalid={evidenceInvalid}
					errorId={evidenceInvalid && hasText(error) ? REVIEW_SETTINGS_ERROR_ID : undefined}
					onChange={(evidenceRequirements) => onChange({ ...reviewFields, evidenceRequirements })}
				/>
			)}

			{/* Under the evidence rather than over the moments: asking by hand is a second way in, not a
			    moment nobody is allowed to tick, and what it reads is the list directly above. */}
			{handAsked && (
				<p className="flex items-start gap-2 text-sm text-muted-foreground">
					<HandIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					<span>
						Anyone can also ask for this review by hand, with “Review this now” on the work itself.
						It reads the same evidence and runs whatever state the work is in .
					</span>
				</p>
			)}

			{hasText(error) && <FieldError id={REVIEW_SETTINGS_ERROR_ID}>{error}</FieldError>}
		</div>
	);
}

/** Strips the occasion's evidence, which is what "guidance only" means server-side. */
export function withoutEvidence(reviewFields: PracticeReviewFields): PracticeReviewFields {
	return { ...reviewFields, evidenceRequirements: [] };
}

export function withRecommendedEvidence(
	reviewFields: PracticeReviewFields,
	options: PracticeWorkTypeDefinitionOptions,
): PracticeReviewFields {
	return reviewFields.evidenceRequirements.length > 0
		? reviewFields
		: { ...reviewFields, evidenceRequirements: [...options.recommendedEvidenceRequirements] };
}
