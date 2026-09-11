import { MoreHorizontal, Pause, Play, Square, Trash2 } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { Survey } from "@/api/types.gen";
import { surveyAvailability } from "@/components/feedback/survey-availability-defs";
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
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export interface SurveyActionsProps {
	survey: Survey;
	/** The page clock, so "End now" is offered exactly while the survey can still end. */
	now: number;
	/** A change to this survey is in flight; the menu waits for it rather than queueing a second. */
	pending: boolean;
	onToggleActive: (survey: Survey, active: boolean) => void;
	/** Confirmed by the reader. The host stamps the end time when it sends the request. */
	onEnd: (survey: Survey) => void;
	/** Confirmed by the reader. */
	onDelete: (survey: Survey) => void;
	/** Menu items placed before the lifecycle ones — the table's "View results" link. */
	children?: ReactNode;
}

type Confirmation = "end" | "delete";

/**
 * One menu for what an administrator can do to a published survey, shared by the table row and
 * the results drawer so the two never disagree about what is offered. The destructive choices
 * confirm; a pause is reversible and does not.
 */
export function SurveyActions({
	survey,
	now,
	pending,
	onToggleActive,
	onEnd,
	onDelete,
	children,
}: SurveyActionsProps) {
	const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
	const availability = surveyAvailability(survey, now);
	const canEnd = availability === "OPEN" || availability === "SCHEDULED";
	const responses = survey.participation.responded + survey.participation.declined;

	return (
		<>
			<DropdownMenu>
				<DropdownMenuTrigger
					disabled={pending}
					render={
						<Button variant="ghost" size="icon-xs" aria-label={`Actions for ${survey.title}`} />
					}
				>
					<MoreHorizontal className="size-4" />
				</DropdownMenuTrigger>
				<DropdownMenuContent align="end">
					<DropdownMenuGroup>
						{children}
						{availability !== "ENDED" && (
							<DropdownMenuItem onClick={() => onToggleActive(survey, !survey.active)}>
								{survey.active ? <Pause className="size-4" /> : <Play className="size-4" />}
								{survey.active ? "Pause" : "Resume"}
							</DropdownMenuItem>
						)}
						{canEnd && (
							<DropdownMenuItem onClick={() => setConfirmation("end")}>
								<Square className="size-4" />
								End now
							</DropdownMenuItem>
						)}
						{(children != null || availability !== "ENDED") && <DropdownMenuSeparator />}
						<DropdownMenuItem variant="destructive" onClick={() => setConfirmation("delete")}>
							<Trash2 className="size-4" />
							Delete
						</DropdownMenuItem>
					</DropdownMenuGroup>
				</DropdownMenuContent>
			</DropdownMenu>

			<AlertDialog
				open={confirmation === "end"}
				onOpenChange={(open) => {
					if (!open) setConfirmation(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>End the survey now?</AlertDialogTitle>
						<AlertDialogDescription>
							Members will no longer be invited. Responses stay.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								setConfirmation(null);
								onEnd(survey);
							}}
						>
							End survey
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>

			<AlertDialog
				open={confirmation === "delete"}
				onOpenChange={(open) => {
					if (!open) setConfirmation(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>
							Delete this survey and its {responses} {responses === 1 ? "response" : "responses"}?
						</AlertDialogTitle>
						<AlertDialogDescription>This cannot be undone.</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							onClick={() => {
								setConfirmation(null);
								onDelete(survey);
							}}
						>
							Delete survey
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}
