import { cn } from "cn";
import {
	Bug,
	ClipboardList,
	ExternalLink,
	Lightbulb,
	MessageSquare,
	MessageSquarePlus,
} from "lucide-react";
import { useId } from "react";

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

import { FEEDBACK_KIND_COPY, type FeedbackKind, READERS } from "./feedback-copy";
import { SURVEY_PURPOSE_DEFS } from "./survey-purpose-defs";
import { surveyEstimate } from "./survey-questions";

/** The public issue tracker, for anyone who would rather talk in the open. */
export const HEPHAESTUS_GITHUB_ISSUES_URL =
	"https://github.com/hephaestus-build/Hephaestus/issues/new/choose";

export interface ProductFeedbackMenuProps {
	invitations: SurveyInvitation[];
	onSendFeedback: (kind: FeedbackKind) => void;
	onOpenSurvey: (surveyId: string) => void;
}

const KINDS: { kind: FeedbackKind; icon: typeof Bug }[] = [
	{ kind: "IDEA", icon: Lightbulb },
	{ kind: "BUG", icon: Bug },
	{ kind: "FEEDBACK", icon: MessageSquare },
];

/**
 * The one place to talk to the Hephaestus team. The trigger is a labelled pill rather than a bare
 * icon so the door is visible; survey invitations wait behind it, counted on the trigger, and
 * nothing opens until the member chooses.
 */
export function ProductFeedbackMenu({
	invitations,
	onSendFeedback,
	onOpenSurvey,
}: ProductFeedbackMenuProps) {
	const id = useId();
	const count = invitations.length;
	return (
		<DropdownMenu>
			<DropdownMenuTrigger
				aria-label={
					count > 0
						? `Feedback, ${count} ${count === 1 ? "survey" : "surveys"} waiting`
						: "Feedback"
				}
				render={
					<Button
						variant="outline"
						className={cn(
							// A 32px square on a phone, where the header is already full; a labelled pill from `sm`.
							"group relative rounded-full px-2 sm:px-3",
							count > 0 && "border-primary/40 bg-primary/5 hover:bg-primary/10",
						)}
					/>
				}
			>
				<MessageSquarePlus
					aria-hidden
					className="transition-transform duration-200 group-hover:-rotate-12 group-hover:scale-110 motion-reduce:transition-none motion-reduce:group-hover:transform-none"
				/>
				<span className="hidden sm:inline">Feedback</span>
				{count > 0 && (
					<span
						aria-hidden
						className="absolute -top-1 -right-1 flex h-4.5 min-w-4.5 items-center justify-center rounded-full bg-primary px-1.5 text-[10px] font-semibold tabular-nums text-primary-foreground sm:static"
					>
						{count}
					</span>
				)}
			</DropdownMenuTrigger>
			<DropdownMenuContent className="w-80" align="end">
				<div className="px-2 pt-1.5 pb-2">
					<p className="text-sm font-medium">Help make Hephaestus better</p>
					<p className="text-xs text-muted-foreground">
						Ideas, bugs and feedback go straight to {READERS}.
					</p>
				</div>
				<DropdownMenuGroup>
					{KINDS.map(({ kind, icon: Icon }) => (
						<DropdownMenuItem
							key={kind}
							className="items-start"
							aria-label={FEEDBACK_KIND_COPY[kind].heading}
							aria-describedby={`${id}-${kind}`}
							onClick={() => onSendFeedback(kind)}
						>
							<Icon className="mt-0.5" />
							<span className="flex min-w-0 flex-col">
								<span>{FEEDBACK_KIND_COPY[kind].heading}</span>
								<span id={`${id}-${kind}`} className="text-xs text-muted-foreground">
									{FEEDBACK_KIND_COPY[kind].detail}
								</span>
							</span>
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
									aria-label={survey.title}
									aria-describedby={`${id}-${survey.id}`}
									onClick={() => onOpenSurvey(survey.id)}
								>
									<ClipboardList className="mt-0.5" />
									<span className="flex min-w-0 flex-col">
										<span className="truncate">{survey.title}</span>
										<span id={`${id}-${survey.id}`} className="text-xs text-muted-foreground">
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
					<DropdownMenuItem
						className="items-start"
						aria-label="Open an issue on GitHub (opens in a new tab)"
						aria-describedby={`${id}-github`}
						render={<a href={HEPHAESTUS_GITHUB_ISSUES_URL} target="_blank" rel="noreferrer" />}
					>
						<GithubIcon className="mt-0.5" />
						<span className="flex min-w-0 flex-col">
							<span className="flex items-center gap-1">
								Open an issue on GitHub
								<ExternalLink aria-hidden className="size-3" />
							</span>
							<span id={`${id}-github`} className="text-xs text-muted-foreground">
								If you'd rather discuss it in the open.
							</span>
						</span>
					</DropdownMenuItem>
				</DropdownMenuGroup>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
