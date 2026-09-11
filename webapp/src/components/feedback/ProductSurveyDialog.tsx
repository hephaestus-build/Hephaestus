import { Link } from "@tanstack/react-router";
import { formatDistance } from "date-fns";
import { useRef } from "react";

import type { Answer, SurveyInvitation } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
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

import { studyOf, SURVEY_PURPOSE_DEFS } from "./survey-purpose-defs";
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

/**
 * The draft belongs to the host so closing keeps it; declining is a ghost button so it never
 * competes with Send. A research survey says whose study the answers join and where to withdraw,
 * because the member agreed to a named organisation, not to surveys in general.
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
	const body = useRef<HTMLDivElement>(null);
	const closes = survey.endsAt
		? `closes ${formatDistance(survey.endsAt, now, { addSuffix: true })}`
		: undefined;
	const research = survey.purpose === "RESEARCH";
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent
				className="sm:max-w-lg"
				// The scroll region is the first tabbable in the popup; the question's first answer is
				// where a member expects to land. Touch keeps Base UI's default so the keyboard stays shut.
				initialFocus={(interaction) =>
					interaction === "touch"
						? null
						: (body.current?.querySelector<HTMLElement>(
								"fieldset:not([hidden]) :is(input, textarea):not([disabled])",
							) ?? null)
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
						<p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
							{research && <StatusBadge def={SURVEY_PURPOSE_DEFS.RESEARCH} />}
							<span>
								{surveyEstimate(survey.questions)}
								{closes ? ` · ${closes}` : ""}
							</span>
						</p>
						<p className="text-xs text-muted-foreground">
							{research ? (
								<>
									Part of {studyOf(survey)}, which you agreed to join. Your answers go to that
									study, linked to your account; this instance's administrators see them as study
									data, not as product feedback. Skip anything you'd rather not answer. You can
									leave the study in{" "}
									<Link
										to="/settings"
										className="underline underline-offset-2"
										onClick={() => onOpenChange(false)}
									>
										User settings
									</Link>{" "}
									— answers already sent stay with the study.
								</>
							) : (
								<>
									Read by this instance's administrators, with your name attached. Not used for
									research, and not sent to the Hephaestus project.
								</>
							)}
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
