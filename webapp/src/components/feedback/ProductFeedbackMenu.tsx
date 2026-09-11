import { Bug, ClipboardList, MessageSquare, MessageSquarePlus } from "lucide-react";

import type { SurveyInvitation } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import type { FeedbackKind } from "./ProductFeedbackDialog";
import { surveyEstimate } from "./survey-questions";

export interface ProductFeedbackMenuProps {
	invitations: SurveyInvitation[];
	onSendFeedback: (kind: FeedbackKind) => void;
	onOpenSurvey: (surveyId: string) => void;
}

/**
 * The one header entry for talking to the instance's administrators. Survey invitations live here
 * rather than in a dialog of their own: the count on the trigger says something is waiting, and
 * nothing opens until the member chooses to.
 */
export function ProductFeedbackMenu({
	invitations,
	onSendFeedback,
	onOpenSurvey,
}: ProductFeedbackMenuProps) {
	const count = invitations.length;
	return (
		<DropdownMenu>
			<DropdownMenuTrigger
				aria-label={
					count > 0
						? `Product feedback, ${count} open ${count === 1 ? "survey" : "surveys"}`
						: "Product feedback"
				}
				render={<Button variant="ghost" size="icon" className="relative" />}
			>
				<MessageSquarePlus />
				{count > 0 && (
					<span
						aria-hidden
						className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-medium tabular-nums text-primary-foreground"
					>
						{count}
					</span>
				)}
			</DropdownMenuTrigger>
			<DropdownMenuContent className="w-72" align="end">
				<DropdownMenuGroup>
					<DropdownMenuItem onClick={() => onSendFeedback("FEEDBACK")}>
						<MessageSquare />
						<span>Send feedback</span>
					</DropdownMenuItem>
					<DropdownMenuItem onClick={() => onSendFeedback("BUG")}>
						<Bug />
						<span>Report a bug</span>
					</DropdownMenuItem>
				</DropdownMenuGroup>
				<DropdownMenuSeparator />
				<DropdownMenuGroup>
					<DropdownMenuLabel>Surveys</DropdownMenuLabel>
					{count === 0 ? (
						<p className="px-1.5 pb-1 text-sm text-muted-foreground">No open surveys.</p>
					) : (
						invitations.map((survey) => (
							<DropdownMenuItem
								key={survey.id}
								className="items-start"
								onClick={() => onOpenSurvey(survey.id)}
							>
								<ClipboardList className="mt-0.5" />
								<span className="flex min-w-0 flex-col">
									<span className="truncate">{survey.title}</span>
									<span className="text-xs text-muted-foreground">
										{surveyEstimate(survey.questions)}
									</span>
								</span>
							</DropdownMenuItem>
						))
					)}
				</DropdownMenuGroup>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
