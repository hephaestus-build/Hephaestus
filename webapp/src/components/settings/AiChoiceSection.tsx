import { type SubmitEvent, useId, useState } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { AiChoiceCards } from "@/components/onboarding/AiChoiceCards";
import type { MemberAiChoice } from "@/components/practice-vocabulary/data-handling-defs";
import { Button } from "@/components/ui/button";
import {
	Questionnaire,
	QuestionnaireDescription,
	QuestionnaireItem,
	QuestionnaireTitle,
} from "@/components/ui/questionnaire";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export interface AiChoiceSectionProps {
	/** The saved answer; `undefined` pre-selects nothing. */
	choice: MemberAiChoice | undefined;
	onSave: (choice: MemberAiChoice) => void;
	isSaving?: boolean;
	isLoading?: boolean;
	isError?: boolean;
	error?: unknown;
	onRetry?: () => void;
}

export function AiChoiceSection({
	choice,
	onSave,
	isSaving = false,
	isLoading = false,
	isError = false,
	error,
	onRetry,
}: AiChoiceSectionProps) {
	return (
		<section className="space-y-4" aria-labelledby="ai-choice-heading">
			<div className="space-y-1">
				<h2 id="ai-choice-heading" className="text-xl font-semibold">
					Your AI choice
				</h2>
				<p className="text-sm text-muted-foreground">
					One answer for every workspace you’re in on this Hephaestus instance.
				</p>
			</div>

			{isError ? (
				<QueryErrorAlert title="Couldn’t load your AI choice" error={error} onRetry={onRetry} />
			) : (
				// Keyed on the saved answer: a refetch that changes it remounts the form with a clean draft.
				<AiChoiceForm
					key={choice ?? "unanswered"}
					choice={choice}
					onSave={onSave}
					isSaving={isSaving}
					isLoading={isLoading}
				/>
			)}
		</section>
	);
}

function AiChoiceForm({
	choice,
	onSave,
	isSaving,
	isLoading,
}: Required<Pick<AiChoiceSectionProps, "choice" | "onSave" | "isSaving" | "isLoading">>) {
	const [draft, setDraft] = useState<MemberAiChoice>();
	const hintId = useId();
	const selected = draft ?? choice;
	const changed = selected !== undefined && selected !== choice;
	const busy = isSaving || isLoading;

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (selected === undefined || !changed || busy) {
			return;
		}
		onSave(selected);
	}

	return (
		<Questionnaire onSubmit={submit}>
			{/* Not `disabled` on the item: the primitive hides a disabled item (`hidden` + `inert`) as
			    "not the current question", so the nested fieldset is what holds the cards still. */}
			<QuestionnaireItem name="ai-choice" required>
				<QuestionnaireTitle>Which AI may handle your work?</QuestionnaireTitle>
				<QuestionnaireDescription>
					Each answer also allows everything stricter than it; the bar on a card shows how far your
					work may travel. Your work is never used for training. Which models run under your answer
					is each workspace’s own setup: Your AI choice in a workspace’s sidebar shows what runs
					there.
				</QuestionnaireDescription>
				{isLoading ? (
					<div className="grid gap-3 sm:grid-cols-2" aria-busy="true">
						<span className="sr-only">Loading…</span>
						<Skeleton className="h-20" />
						<Skeleton className="h-20" />
						<Skeleton className="h-20" />
						<Skeleton className="h-20" />
					</div>
				) : (
					<fieldset disabled={busy} className="min-w-0 disabled:opacity-50">
						<AiChoiceCards choice={selected} onChoice={setDraft} />
					</fieldset>
				)}
			</QuestionnaireItem>

			<footer className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-4">
				<Button type="submit" disabled={!changed || busy} aria-describedby={hintId}>
					{isSaving && <Spinner />}
					{isSaving ? "Saving…" : "Save"}
				</Button>
				<p id={hintId} className="text-sm text-muted-foreground">
					Applies in all your workspaces. Change it any time.
				</p>
			</footer>
		</Questionnaire>
	);
}
