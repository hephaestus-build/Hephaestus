import {
	Bug,
	ClipboardList,
	ExternalLink,
	Lightbulb,
	MessageSquare,
	MessageSquarePlus,
} from "lucide-react";

import type { SurveyInvitation } from "@/api/types.gen";
import { GithubIcon } from "@/components/icons/brand";
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
import { SURVEY_PURPOSE_DEFS } from "./survey-purpose-defs";
import { surveyEstimate } from "./survey-questions";

/** Where the Hephaestus project itself takes bug reports and feature ideas from anyone. */
export const HEPHAESTUS_GITHUB_ISSUES_URL =
	"https://github.com/hephaestus-build/Hephaestus/issues/new/choose";

export interface ProductFeedbackMenuProps {
	invitations: SurveyInvitation[];
	onSendFeedback: (kind: FeedbackKind) => void;
	onOpenSurvey: (surveyId: string) => void;
}

const KINDS: { kind: FeedbackKind; label: string; icon: typeof Bug }[] = [
	{ kind: "IDEA", label: "Share an idea", icon: Lightbulb },
	{ kind: "BUG", label: "Report a bug", icon: Bug },
	{ kind: "FEEDBACK", label: "Send feedback", icon: MessageSquare },
];

/**
 * The one place to talk to the people behind this instance — and, one step further, to the
 * project. Survey invitations wait here rather than in a dialog of their own: the count on the
 * trigger says something is waiting, and nothing opens until the member chooses to.
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
						? `Feedback, ${count} ${count === 1 ? "survey" : "surveys"} waiting`
						: "Feedback"
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
					<DropdownMenuLabel>To this instance's administrators</DropdownMenuLabel>
					{KINDS.map(({ kind, label, icon: Icon }) => (
						<DropdownMenuItem key={kind} onClick={() => onSendFeedback(kind)}>
							<Icon />
							<span>{label}</span>
						</DropdownMenuItem>
					))}
				</DropdownMenuGroup>
				{count > 0 && (
					<>
						<DropdownMenuSeparator />
						<DropdownMenuGroup>
							<DropdownMenuLabel>Surveys waiting for you</DropdownMenuLabel>
							{invitations.map((survey) => (
								<DropdownMenuItem
									key={survey.id}
									className="items-start"
									onClick={() => onOpenSurvey(survey.id)}
								>
									<ClipboardList className="mt-0.5" />
									<span className="flex min-w-0 flex-col">
										<span className="truncate">{survey.title}</span>
										<span className="text-xs text-muted-foreground">
											{survey.purpose === "RESEARCH" && `${SURVEY_PURPOSE_DEFS.RESEARCH.label} · `}
											{surveyEstimate(survey.questions)}
										</span>
									</span>
								</DropdownMenuItem>
							))}
						</DropdownMenuGroup>
					</>
				)}
				<DropdownMenuSeparator />
				<DropdownMenuGroup>
					<DropdownMenuLabel>To the Hephaestus project</DropdownMenuLabel>
					<DropdownMenuItem
						className="items-start"
						render={<a href={HEPHAESTUS_GITHUB_ISSUES_URL} target="_blank" rel="noreferrer" />}
					>
						<GithubIcon className="mt-0.5" />
						<span className="flex min-w-0 flex-col">
							<span className="flex items-center gap-1">
								Open an issue on GitHub
								<ExternalLink aria-hidden className="size-3" />
								<span className="sr-only">(opens in a new tab)</span>
							</span>
							<span className="text-xs text-muted-foreground">
								Public, for bugs and ideas that concern every instance.
							</span>
						</span>
					</DropdownMenuItem>
				</DropdownMenuGroup>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
