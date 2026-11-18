import { use, useId, useState } from "react";
import { UnsavedPracticeDraft } from "@/components/admin/practice-editor/unsaved-practice-draft";

import type {
	AcceptPracticeReleaseRequest,
	PracticeDefinition,
	PracticeReleaseProposal,
} from "@/api/types.gen";
import {
	PRACTICE_DEFINITION_FIELDS,
	type PracticeDefinitionField,
} from "@/components/admin/practices/practice-definition-fields";
import { PracticeDefinitionFieldValue } from "@/components/admin/practices/PracticeDefinitionFieldValue";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

type Choices = AcceptPracticeReleaseRequest["choices"];
type Source = Choices[string];

export interface PracticeReleaseReviewProps {
	proposal: PracticeReleaseProposal;
	pending: "accept" | "decline" | null;
	/**
	 * What declining leaves in place and how to undo it. The instance catalog and a workspace both
	 * render this review, and each has its own source of updates and its own way back to it.
	 */
	declineNote: string;
	/** Why this update cannot be accepted here, when it cannot. Declining stays available. */
	acceptUnavailable?: string;
	onAccept: (choices: Choices) => void;
	onDecline: () => void;
}

export function PracticeReleaseReview({
	proposal,
	pending,
	declineNote,
	acceptUnavailable,
	onAccept,
	onDecline,
}: PracticeReleaseReviewProps) {
	const id = useId();
	// Without the adopted version, local edits cannot be distinguished from the update's changes.
	const base: PracticeDefinition | null = proposal.base;
	const baseKnown = base !== null;
	const [choices, setChoices] = useState(
		() =>
			new Map<PracticeDefinitionField, Source>(
				proposal.fields
					.filter((field) => baseKnown && field.offeredChanged && !field.conflict)
					.map((field) => [field.field, "OFFERED"]),
			),
	);
	const ready = proposal.fields.every((field) => !field.offeredChanged || choices.has(field.field));
	const unsavedDraft = use(UnsavedPracticeDraft);
	const [confirmingAccept, setConfirmingAccept] = useState(false);
	const accept = () => onAccept(Object.fromEntries(choices));
	const submit = () => {
		if (!ready || acceptUnavailable !== undefined) {
			return;
		}
		if (unsavedDraft) {
			setConfirmingAccept(true);
			return;
		}
		accept();
	};

	return (
		<section
			className="min-w-0 space-y-5"
			aria-label={`Review update for ${proposal.offered.name}`}
		>
			<div className="space-y-1">
				<h2 className="text-lg font-semibold">{proposal.offered.name}</h2>
				<p className="text-sm text-muted-foreground">
					Compare each changed field. Your current practice stays in use until you accept this
					update.
				</p>
			</div>
			{!baseKnown && (
				<Alert variant="warning">
					<AlertTitle>The version you adopted is not recorded</AlertTitle>
					<AlertDescription>
						We cannot tell your edits from the changes in this update. Choose a version for each
						changed field.
					</AlertDescription>
				</Alert>
			)}
			<Table bordered className="min-w-220 table-fixed">
				<caption className="sr-only">
					Changed fields in the adopted, current, and proposed versions
				</caption>
				<TableHeader>
					<TableRow variant="static">
						<TableHead scope="col" className="w-38">
							Field
						</TableHead>
						<TableHead scope="col">Base</TableHead>
						<TableHead scope="col">Current</TableHead>
						<TableHead scope="col">Proposed</TableHead>
						<TableHead scope="col" className="w-42">
							Use
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{proposal.fields.map(({ field, offeredChanged, conflict }) => (
						<TableRow key={field} variant="static">
							{/* A row header, muted like the column headers: the field is the key, and the three
							    versions beside it are what the reader compares. */}
							<TableHead scope="row" variant="body">
								<span className="flex flex-wrap items-center gap-x-2 gap-y-1">
									{PRACTICE_DEFINITION_FIELDS[field].label}
									{conflict && <Badge variant="warning">Conflict</Badge>}
									{!offeredChanged && <Badge variant="secondary">Local edit</Badge>}
								</span>
							</TableHead>
							<TableCell className="align-top whitespace-normal">
								{base === null ? (
									<span className="text-xs text-muted-foreground">Not recorded</span>
								) : (
									<PracticeDefinitionFieldValue definition={base} field={field} />
								)}
							</TableCell>
							<TableCell className="align-top whitespace-normal">
								<PracticeDefinitionFieldValue definition={proposal.current} field={field} />
							</TableCell>
							<TableCell className="align-top whitespace-normal">
								<PracticeDefinitionFieldValue definition={proposal.offered} field={field} />
							</TableCell>
							<TableCell className="align-top">
								{offeredChanged ? (
									<RadioGroup
										aria-label={`Use a version for ${PRACTICE_DEFINITION_FIELDS[field].label}`}
										value={choices.get(field) ?? ""}
										disabled={pending !== null}
										onValueChange={(choice) => {
											if (choice === "CURRENT" || choice === "OFFERED") {
												setChoices((previous) => new Map(previous).set(field, choice));
											}
										}}
									>
										<label className="flex items-center gap-2" htmlFor={`${id}-${field}-current`}>
											<RadioGroupItem id={`${id}-${field}-current`} value="CURRENT" /> Current
										</label>
										<label className="flex items-center gap-2" htmlFor={`${id}-${field}-offered`}>
											<RadioGroupItem id={`${id}-${field}-offered`} value="OFFERED" /> Proposed
										</label>
									</RadioGroup>
								) : (
									<span className="text-xs text-muted-foreground">Keep current</span>
								)}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
			{acceptUnavailable !== undefined && (
				<p id={`${id}-accept-unavailable`} className="text-sm text-muted-foreground">
					{acceptUnavailable}
				</p>
			)}
			<div className="flex flex-wrap gap-2">
				<Button
					pending={pending === "accept"}
					type="button"
					disabled={!ready || pending !== null || acceptUnavailable !== undefined}
					// Focusable, so a keyboard reader reaches the button and hears why it is unavailable.
					focusableWhenDisabled={acceptUnavailable !== undefined}
					aria-describedby={
						acceptUnavailable === undefined ? undefined : `${id}-accept-unavailable`
					}
					onClick={submit}
				>
					{pending === "accept" ? "Accepting selected fields…" : "Accept selected fields"}
				</Button>
				<Button
					pending={pending === "decline"}
					type="button"
					variant="outline"
					disabled={pending !== null}
					onClick={onDecline}
				>
					{pending === "decline" ? "Declining update…" : "Decline this update"}
				</Button>
			</div>
			<p className="text-xs text-muted-foreground">{declineNote}</p>
			<AlertDialog open={confirmingAccept} onOpenChange={setConfirmingAccept}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Accept selected fields?</AlertDialogTitle>
						<AlertDialogDescription>
							The practice reloads with the fields you chose. Your unsaved changes to the practice
							are discarded.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								setConfirmingAccept(false);
								accept();
							}}
						>
							Accept selected fields
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</section>
	);
}
