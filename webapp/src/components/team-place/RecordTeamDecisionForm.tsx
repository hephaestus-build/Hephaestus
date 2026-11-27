import { type SubmitEvent, useId, useState } from "react";

import { definitionFor } from "@/components/common/status-def";
import {
	TEAM_DECISION_STATE_DEFS,
	type TeamDecisionState,
} from "@/components/practice-vocabulary/team-decision-state-defs";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";

const STATES: readonly TeamDecisionState[] = ["OPEN", "ACTED_ON", "DISMISSED"];

/** The optional prompt, shown only as the note's placeholder and only where the answer leaves a real choice. */
const PROMPT = "What will you try, why, and what would you look for in the next version?";

/** Why the last Record did not save. `SUPERSEDED`: another member recorded a newer decision first. */
export type RecordDecisionError = "SUPERSEDED" | "FAILED";

const ERROR_TEXT: Readonly<Record<RecordDecisionError, string>> = {
	SUPERSEDED:
		"Another member recorded a newer decision on this problem. Read it, then record yours if it still applies.",
	FAILED: "Your decision was not recorded. Try again.",
};

export interface RecordedDecision {
	state: TeamDecisionState;
	note: string;
	/** The record the member saw when the form opened; a newer one makes the server refuse this one. */
	supersedesId?: string;
}

export interface RecordTeamDecisionFormProps {
	/** The problem the decision is about, as the review stated it. */
	problemSummary: string;
	/** The team project whose current members read the record, by its name without "Team". */
	teamName: string;
	/** The state the problem has now, preselected. A new decision starts Open. */
	currentState?: TeamDecisionState;
	/** The current record of the problem when the form opened, if one exists. */
	currentRecordId?: string;
	/** The answer leaves the team a real choice, so the note shows the prompt as its placeholder. */
	prompt?: boolean;
	/** A note Hephaestus drafted. "Use this draft" copies it into the note for editing; it records nothing. */
	draftNote?: string;
	/** The member's explicit Record, the only write. */
	onRecord: (decision: RecordedDecision) => void;
	/** The Record request is in flight. */
	pending?: boolean;
	recordError?: RecordDecisionError;
}

/**
 * A member records a team decision on one problem: a state and one note in their own words. Nothing is written until
 * they press Record. A draft from Hephaestus only fills the note. Key the form on the problem, so another problem
 * starts empty.
 */
export function RecordTeamDecisionForm({
	problemSummary,
	teamName,
	currentState = "OPEN",
	currentRecordId,
	prompt = false,
	draftNote,
	onRecord,
	pending = false,
	recordError,
}: RecordTeamDecisionFormProps) {
	const [state, setState] = useState<TeamDecisionState>(currentState);
	const [note, setNote] = useState("");
	// The member's own words that a draft replaced, so "Use this draft" never loses them.
	const [replaced, setReplaced] = useState<string | undefined>(undefined);
	const id = useId();

	function applyDraft(draft: string) {
		setReplaced(note.trim() === "" || note === draft ? replaced : note);
		setNote(draft);
	}

	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		onRecord({ state, note: note.trim(), supersedesId: currentRecordId });
	}

	return (
		<form aria-label="Record a team decision" className="flex flex-col gap-4" onSubmit={submit}>
			<p className="text-sm text-foreground" dir="auto">
				{problemSummary}
			</p>
			<FieldSet>
				<FieldLegend variant="label">Decision</FieldLegend>
				<RadioGroup<TeamDecisionState> value={state} onValueChange={setState}>
					{STATES.map((value) => {
						const def = definitionFor(TEAM_DECISION_STATE_DEFS, value);
						const optionId = `${id}-${value}`;
						return (
							<FieldLabel key={value} htmlFor={optionId}>
								<Field orientation="horizontal">
									<RadioGroupItem id={optionId} value={value} />
									<FieldContent>
										<FieldTitle>{def.label}</FieldTitle>
										<FieldDescription>{def.description}</FieldDescription>
									</FieldContent>
								</Field>
							</FieldLabel>
						);
					})}
				</RadioGroup>
			</FieldSet>
			<Field>
				<FieldLabel htmlFor={`${id}-note`}>Note</FieldLabel>
				<Textarea
					id={`${id}-note`}
					value={note}
					onChange={(event) => setNote(event.target.value)}
					placeholder={prompt ? PROMPT : undefined}
					rows={4}
				/>
			</Field>
			{draftNote !== undefined && (
				<div className="flex flex-col gap-2 rounded-lg border p-3 text-sm">
					<p className="font-medium text-foreground">Draft note from Hephaestus</p>
					<p className="whitespace-pre-line text-muted-foreground" dir="auto">
						{draftNote}
					</p>
					<div className="flex flex-wrap gap-2">
						<Button type="button" variant="outline" size="sm" onClick={() => applyDraft(draftNote)}>
							Use this draft
						</Button>
						{replaced !== undefined && note === draftNote && (
							<Button
								type="button"
								variant="ghost"
								size="sm"
								onClick={() => {
									setNote(replaced);
									setReplaced(undefined);
								}}
							>
								Restore your note
							</Button>
						)}
					</div>
				</div>
			)}
			{recordError !== undefined && (
				<Alert variant="destructive">
					<AlertDescription>{ERROR_TEXT[recordError]}</AlertDescription>
				</Alert>
			)}
			<div className="flex flex-col gap-2">
				<p className="text-sm text-muted-foreground">
					Every current member of Team <bdi>{teamName}</bdi> sees this record and your name on it.
				</p>
				<Button type="submit" className="w-fit" pending={pending}>
					{pending ? "Recording…" : "Record"}
				</Button>
			</div>
		</form>
	);
}
