import { Link } from "@tanstack/react-router";
import { Bug, History, Lightbulb, MessageSquare, MessageSquarePlus } from "lucide-react";
import { useId } from "react";

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

export interface ProductFeedbackMenuProps {
	onSendFeedback: (kind: FeedbackKind) => void;
	/** The workspace the header keeps, so Your product feedback stays in it. */
	workspaceSlug?: string;
}

const KINDS: { kind: FeedbackKind; icon: typeof Bug }[] = [
	{ kind: "IDEA", icon: Lightbulb },
	{ kind: "BUG", icon: Bug },
	{ kind: "FEEDBACK", icon: MessageSquare },
];

/**
 * The header's one entry to product feedback: the three kinds to send, and the person's own reports.
 * The static link to the upstream issue tracker is gone: reports reach GitHub through the instance.
 */
export function ProductFeedbackMenu({ onSendFeedback, workspaceSlug }: ProductFeedbackMenuProps) {
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
						render={
							<Link
								to="/product-feedback"
								search={{ chromeWorkspace: workspaceSlug }}
								className="[&]:no-underline"
							/>
						}
					>
						<History />
						<span>Your product feedback</span>
					</DropdownMenuItem>
				</DropdownMenuGroup>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
