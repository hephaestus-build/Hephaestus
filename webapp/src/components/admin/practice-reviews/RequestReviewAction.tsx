import { PlayIcon } from "lucide-react";
import { useRef, useState } from "react";

import { cn } from "cn";
import { ROW_CONTROL_FOCUS } from "@/components/common/focus";
import { inertBackground } from "@/components/common/inert-background";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/** Request review's icon, which the actions column's (i) also leads its line with. */
export const RequestReviewIcon = PlayIcon;

/** What a row's "Request review" asks about, and whether it can ask now. */
export interface RowReviewRequest {
	/** The work by name, as the confirmation says it inside a sentence: "pull request #1423". */
	work: string;
	/** Starts the review; absent while `blocked` says why it cannot. */
	onRequest?: () => void;
	/** Why no review can be asked for now, shown over the disabled button. */
	blocked?: string;
	/** An ask about this work is in flight. */
	requesting?: boolean;
}

/** Why a row cannot ask, in the words its tooltip uses. */
export const REQUEST_BLOCKED = {
	active: "A review of this work is already queued or running.",
	kind: "A review can be requested only for pull requests, merge requests and issues.",
	noWork: "This review names no work to review again.",
} as const;

export interface RequestReviewActionProps {
	request: RowReviewRequest;
	/** The button's words, for a sheet's footer; absent, a row's icon button named by the work. */
	label?: string;
}

/**
 * A row's third action, after Track and Trace: asks for a new review of the row's work. With a
 * label, it is the same ask in the footer of the work and review sheets. It starts an agent job, so
 * it asks first. While a review of the
 * work is queued or running, or the work is of a kind nobody can ask about, it stays in place
 * disabled and says why, so the row's actions never shift.
 *
 * The confirmation is a real modal: focus stays inside it, the table behind is `inert` while it is
 * open, Escape and Cancel close it, and focus returns to the button. A press inside it never reaches
 * the row it was opened from, although React passes the portal's events up to that row.
 */
export function RequestReviewAction({ request, label }: RequestReviewActionProps) {
	// Said inside a sentence: "Request a review of pull request #1423?"
	const work = `${request.work.charAt(0).toLowerCase()}${request.work.slice(1)}`;
	// A row's button is named by the work, since that is what it reviews: two rows of one work make
	// the same ask. A sheet's button is about its own work, so its words name it.
	const name = label === undefined ? `Request review of ${work}` : undefined;
	const size = label === undefined ? "icon" : "default";
	const className = cn(
		label === undefined && ROW_CONTROL_FOCUS,
		"aria-disabled:cursor-not-allowed aria-disabled:opacity-50",
	);
	const content = (
		<>
			<RequestReviewIcon aria-hidden />
			{label}
		</>
	);
	const { onRequest, blocked, requesting = false } = request;
	// Controlled, because the confirming button is not a close button: it closes the dialog itself.
	const [open, setOpen] = useState(false);
	const trigger = useRef<HTMLButtonElement>(null);
	if (onRequest === undefined || blocked !== undefined || requesting) {
		const reason = requesting ? "Requesting a review…" : (blocked ?? REQUEST_BLOCKED.kind);
		return (
			<Tooltip>
				<TooltipTrigger
					render={
						<Button
							type="button"
							variant="outline"
							size={size}
							disabled
							// Focusable, so a keyboard reaches the reason too.
							focusableWhenDisabled
							aria-label={name}
							className={className}
						/>
					}
				>
					{content}
				</TooltipTrigger>
				<TooltipContent>{reason}</TooltipContent>
			</Tooltip>
		);
	}
	return (
		<AlertDialog open={open} onOpenChange={setOpen}>
			<AlertDialogTrigger
				render={
					<Button
						ref={trigger}
						type="button"
						variant="outline"
						size={size}
						aria-label={name}
						title={label === undefined ? "Request review" : undefined}
						className={className}
					/>
				}
			>
				{content}
			</AlertDialogTrigger>
			<AlertDialogContent
				ref={inertBackground}
				finalFocus={trigger}
				// React passes a portal's events to its React parents: the row would open its sheet.
				onClick={(event) => event.stopPropagation()}
			>
				<AlertDialogHeader>
					<AlertDialogTitle>Request a review of {work}?</AlertDialogTitle>
					<AlertDialogDescription>
						Hephaestus reviews {work} now against the practices that apply to it, as a requested
						review. It runs as an agent job and counts toward this workspace&rsquo;s AI usage. If
						the workspace settings stop it, you see why.
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogAction
						onClick={() => {
							setOpen(false);
							onRequest();
						}}
					>
						Request review
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
