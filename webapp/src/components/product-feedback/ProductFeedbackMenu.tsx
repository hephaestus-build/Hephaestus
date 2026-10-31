import { Bug, ExternalLink, Lightbulb, MessageSquare, MessageSquarePlus } from "lucide-react";
import { useId } from "react";

import { GitHubIcon } from "@/components/icons/brand";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import { FEEDBACK_KIND_COPY, type FeedbackKind, READERS } from "./feedback-copy";

/** The public issue tracker, for anyone who would rather talk in the open. */
export const HEPHAESTUS_GITHUB_ISSUES_URL =
	"https://github.com/hephaestus-build/Hephaestus/issues/new/choose";

export interface ProductFeedbackMenuProps {
	onSendFeedback: (kind: FeedbackKind) => void;
}

const KINDS: { kind: FeedbackKind; icon: typeof Bug }[] = [
	{ kind: "IDEA", icon: Lightbulb },
	{ kind: "BUG", icon: Bug },
	{ kind: "FEEDBACK", icon: MessageSquare },
];

export function ProductFeedbackMenu({ onSendFeedback }: ProductFeedbackMenuProps) {
	const id = useId();
	return (
		<DropdownMenu>
			<DropdownMenuTrigger
				aria-label="Product feedback"
				render={<Button variant="outline" shape="pill" className="w-8 sm:w-auto" />}
			>
				<MessageSquarePlus data-icon="inline-start" aria-hidden />
				<span className="hidden sm:inline">Product feedback</span>
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
				<DropdownMenuSeparator />
				<DropdownMenuGroup>
					<DropdownMenuItem
						className="items-start"
						aria-label="Open an issue on GitHub (opens in a new tab)"
						aria-describedby={`${id}-github`}
						render={<a href={HEPHAESTUS_GITHUB_ISSUES_URL} target="_blank" rel="noreferrer" />}
					>
						<GitHubIcon className="mt-0.5" />
						<span className="flex min-w-0 flex-col">
							<span className="flex items-center gap-1">
								Open an issue on GitHub
								<ExternalLink aria-hidden className="size-3" />
							</span>
							<span id={`${id}-github`} className="text-xs text-muted-foreground">
								Discuss your feedback in public instead.
							</span>
						</span>
					</DropdownMenuItem>
				</DropdownMenuGroup>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
