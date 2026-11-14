import { HandIcon, InboxIcon } from "lucide-react";

import type { PracticeItemTypeOption, PracticeReadSource, ReviewOn } from "@/api/types.gen";
import { FieldError } from "@/components/ui/field";
import { itemKindIcon } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { PracticeReadsEditor } from "./PracticeReadsEditor";
import { REVIEW_ON_ID_PREFIX, type ReviewedByKind, reviewOnFieldId } from "./review-settings";
import { ReviewOnEditor } from "./ReviewOnEditor";

/** Also the `aria-describedby` target: focus lands on the control, not on the rendered message. */
const REVIEW_SETTINGS_ERROR_ID = "practice-review-settings-error";

export interface ReviewSettings {
	reviewOn: ReviewOn;
	reads: PracticeReadSource[];
}

export interface PracticeReviewSettingsEditorProps {
	itemType: PracticeItemTypeOption;
	value: ReviewSettings;
	onChange: (value: ReviewSettings) => void;
	reviewedBy: ReviewedByKind;
	/** Whether this practice's feedback can appear on the work: its kind allows it and the practice is about the author. */
	feedbackOnWork: boolean;
	/** Whether the practice is about a developer. A practice about the work itself has nobody to give feedback to. */
	aboutADeveloper: boolean;
	error?: string;
	/** The control the error points at, so the field it names is the one that opens. */
	errorFocusId?: string;
	disabled?: boolean;
}

export function PracticeReviewSettingsEditor({
	itemType,
	value,
	onChange,
	reviewedBy,
	feedbackOnWork,
	aboutADeveloper,
	error,
	errorFocusId,
	disabled = false,
}: PracticeReviewSettingsEditorProps) {
	const KindIcon = itemKindIcon(itemType.kind);
	const errorFor = (field: "events" | "state" | "reads") =>
		errorFocusId === reviewOnFieldId(field) && hasText(error)
			? REVIEW_SETTINGS_ERROR_ID
			: undefined;
	const automated = reviewedBy === "HEPHAESTUS";

	return (
		<div className="space-y-4">
			<div className="space-y-1.5">
				<p className="flex items-center gap-2 text-sm font-medium">
					<KindIcon className="size-4 text-muted-foreground" aria-hidden />
					{itemType.label}
				</p>
				<p className="text-sm text-muted-foreground">
					{automated
						? "Every review first captures the sources it must read. When one cannot be captured, the review is withheld rather than guessed."
						: "No automated review runs, so this practice reads nothing."}
				</p>
			</div>

			<ReviewOnEditor
				itemType={itemType}
				reviewOn={value.reviewOn}
				onChange={(reviewOn) => onChange({ ...value, reviewOn })}
				eventsErrorId={errorFor("events")}
				stateErrorId={errorFor("state")}
				disabled={disabled}
			/>

			{automated && (
				<PracticeReadsEditor
					readSources={itemType.readSources}
					sources={value.reads}
					idPrefix={REVIEW_ON_ID_PREFIX}
					disabled={disabled}
					invalid={errorFocusId === reviewOnFieldId("reads")}
					errorId={errorFor("reads")}
					onChange={(reads) => onChange({ ...value, reads })}
				/>
			)}

			{/* Under the reads rather than over the events: a request is a second way in, not an event
			    nobody is allowed to tick, and what it reads is the list directly above. */}
			{automated && (
				<p className="flex items-start gap-2 text-sm text-muted-foreground">
					<HandIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					<span>
						Anyone can also request this review on the work itself. A requested review ignores
						Review on and When, and still checks Applies when.
					</span>
				</p>
			)}

			{automated && !feedbackOnWork && (
				<p className="flex items-start gap-2 text-sm text-muted-foreground">
					<InboxIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
					<span>
						This practice’s feedback never appears on the work itself.
						{aboutADeveloper &&
							" The developer it is about gets it on their Practice profile and from Hephaestus when they ask."}
					</span>
				</p>
			)}

			{hasText(error) && <FieldError id={REVIEW_SETTINGS_ERROR_ID}>{error}</FieldError>}
		</div>
	);
}
