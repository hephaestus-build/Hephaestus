import { formatDistance } from "date-fns";
import type { Answer, SurveyInvitation } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";

import { ProductSurveyForm } from "./ProductSurveyForm";
import { type AnswerDraft, isDraftComplete, surveyEstimate, toAnswers } from "./survey-questions";

export interface ProductSurveyDialogProps {
	survey: SurveyInvitation;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/** The in-progress answers, owned by the caller so closing the dialog keeps them. */
	draft: AnswerDraft;
	onDraftChange: (draft: AnswerDraft) => void;
	isSubmitting: boolean;
	error?: string;
	onSubmit: (answers: Answer[]) => void | Promise<void>;
	onDecline: () => void | Promise<void>;
}

/**
 * One survey, opened on purpose from the header. Closing keeps the draft; declining is a separate,
 * undoable decision, kept visually quiet so it never competes with the answers.
 */
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
	const complete = isDraftComplete(survey.questions, draft);
	const closes = survey.endsAt
		? `Closes ${formatDistance(survey.endsAt, now, { addSuffix: true })}`
		: undefined;
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="sm:max-w-lg">
				<DialogForm
					aria-busy={isSubmitting}
					onSubmit={(event) => {
						event.preventDefault();
						if (complete && !isSubmitting) void onSubmit(toAnswers(survey.questions, draft));
					}}
				>
					<DialogHeader>
						<DialogTitle className="break-words">{survey.title}</DialogTitle>
						<DialogDescription className="break-words">{survey.description}</DialogDescription>
						<p className="text-xs text-muted-foreground">
							{surveyEstimate(survey.questions)}
							{closes ? ` · ${closes}` : ""}
						</p>
					</DialogHeader>
					<DialogBody className="flex flex-col gap-5 py-1">
						<ProductSurveyForm
							questions={survey.questions}
							draft={draft}
							onDraftChange={onDraftChange}
							disabled={isSubmitting}
							idPrefix={`survey-${survey.id}`}
						/>
						<p className="text-xs text-muted-foreground">
							Your answers are linked to your account and visible only to this instance's
							administrators. They are not anonymous and not used for research. Closing keeps your
							draft until you reload, switch workspace, or sign out.
						</p>
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
						<Button type="submit" disabled={!complete || isSubmitting}>
							{isSubmitting && <Spinner />}
							{isSubmitting ? "Sending…" : "Send answers"}
						</Button>
					</DialogFooter>
				</DialogForm>
			</DialogContent>
		</Dialog>
	);
}
