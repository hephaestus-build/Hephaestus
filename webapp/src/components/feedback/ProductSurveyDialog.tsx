import { formatDistance } from "date-fns";
import { useRef } from "react";

import type { Answer, SurveyInvitation } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";

import { type SurveyResponseDraft, surveyEstimate } from "./survey-questions";
import { SurveyQuestionnaire } from "./SurveyQuestionnaire";

export interface ProductSurveyDialogProps {
	survey: SurveyInvitation;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	draft: SurveyResponseDraft;
	onDraftChange: (draft: SurveyResponseDraft) => void;
	isSubmitting: boolean;
	error?: string;
	onSubmit: (answers: Answer[]) => void | Promise<void>;
	onDecline: () => void | Promise<void>;
}

/** The draft belongs to the host so closing keeps it; declining is a ghost button so it never competes with Send. */
export function ProductSurveyDialog({
	survey,
	open,
	onOpenChange,
	draft,
	onDraftChange,
	isSubmitting,
	error,
	onSubmit,
	onDecline,
}: ProductSurveyDialogProps) {
	const now = useNow();
	const body = useRef<HTMLDivElement>(null);
	const closes = survey.endsAt
		? `Closes ${formatDistance(survey.endsAt, now, { addSuffix: true })}`
		: undefined;
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent
				className="sm:max-w-lg"
				// The scroll region is the first tabbable in the popup; the question's first answer is
				// where a member expects to land.
				initialFocus={() =>
					body.current?.querySelector<HTMLElement>(
						"fieldset:not([hidden]) :is(input, textarea):not([disabled])",
					) ?? false
				}
			>
				<SurveyQuestionnaire
					className="contents"
					questions={survey.questions}
					draft={draft}
					onDraftChange={onDraftChange}
					onSubmit={(answers) => void onSubmit(answers)}
					disabled={isSubmitting}
				>
					<DialogHeader>
						<DialogTitle className="break-words">{survey.title}</DialogTitle>
						<DialogDescription className="break-words">{survey.description}</DialogDescription>
						<p className="text-xs text-muted-foreground">
							{surveyEstimate(survey.questions)}
							{closes ? ` · ${closes}` : ""} · Answers are linked to your account and visible only
							to this instance's administrators; they are not used for research.
						</p>
					</DialogHeader>
					<DialogBody ref={body} className="flex flex-col gap-4 py-1">
						<SurveyQuestionnaire.Progress />
						<SurveyQuestionnaire.Items />
						<p role="alert" className="text-sm text-destructive empty:hidden">
							{error}
						</p>
					</DialogBody>
					<DialogFooter className="sm:justify-between">
						<Button
							type="button"
							variant="ghost"
							className="text-muted-foreground"
							disabled={isSubmitting}
							onClick={() => void onDecline()}
						>
							Decline survey
						</Button>
						<SurveyQuestionnaire.Actions
							className="w-auto"
							submitLabel={
								isSubmitting ? (
									<>
										<Spinner />
										Sending…
									</>
								) : (
									"Send answers"
								)
							}
						/>
					</DialogFooter>
				</SurveyQuestionnaire>
			</DialogContent>
		</Dialog>
	);
}
