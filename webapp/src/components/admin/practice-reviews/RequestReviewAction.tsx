import { PlayIcon } from "lucide-react";

import { cn } from "cn";
import { ROW_CONTROL_FOCUS } from "@/components/common/focus";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/** Request review's icon, which the actions column's (i) also leads its line with. */
export const RequestReviewIcon = PlayIcon;

/** What a row's "Request review" asks about, and whether it can ask now. */
export interface RowReviewRequest {
	/** The work by name, as a row's button says it inside its name: "pull request #1423". */
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

/** What the button says while its ask is in flight, as a member's "Request review" says it. */
const REQUESTING = "Requesting review…";

export interface RequestReviewActionProps {
	request: RowReviewRequest;
	/** The button's words, for a sheet's footer; absent, a row's icon button named by the work. */
	label?: string;
}

/**
 * A row's third action, after Track and Trace: asks for a new review of the row's work. With a
 * label, it is the same ask in the footer of the work and review sheets. A press asks at once, as a
 * member's "Request review" on Activity does: the server refuses a repeat while a review of the work
 * is queued or running, and the answer arrives as a toast or an inline refusal. While a review of the
 * work is queued or running, or the work is of a kind nobody can ask about, it stays in place
 * disabled and says why, so the row's actions never shift.
 */
export function RequestReviewAction({ request, label }: RequestReviewActionProps) {
	// Said inside a name: "Request review of pull request #1423".
	const work = `${request.work.charAt(0).toLowerCase()}${request.work.slice(1)}`;
	// A row's button is named by the work, since that is what it reviews: two rows of one work make
	// the same ask. A sheet's button is about its own work, so its words name it.
	const name = label === undefined ? `Request review of ${work}` : undefined;
	const size = label === undefined ? "icon" : "default";
	const className = cn(
		label === undefined && ROW_CONTROL_FOCUS,
		"aria-disabled:cursor-not-allowed aria-disabled:opacity-50",
	);
	const { onRequest, blocked, requesting = false } = request;
	const content = (
		<>
			{requesting ? <Spinner /> : <RequestReviewIcon aria-hidden />}
			{label !== undefined && (requesting ? REQUESTING : label)}
		</>
	);
	// Why the button cannot ask now, if it cannot.
	const reason = requesting
		? REQUESTING
		: (blocked ?? (onRequest === undefined ? REQUEST_BLOCKED.kind : undefined));
	// One button in every state, so a press that starts the ask keeps the reader's focus on it.
	return (
		<Tooltip disabled={reason === undefined}>
			<TooltipTrigger
				render={
					<Button
						type="button"
						variant="outline"
						size={size}
						disabled={reason !== undefined}
						// Focusable while disabled, so a keyboard reaches the reason too.
						focusableWhenDisabled={reason !== undefined}
						aria-label={name}
						title={reason === undefined && label === undefined ? "Request review" : undefined}
						className={className}
						onClick={onRequest}
					/>
				}
			>
				{content}
			</TooltipTrigger>
			<TooltipContent>{reason}</TooltipContent>
		</Tooltip>
	);
}
