import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { HephaestusMark } from "@/components/brand/HephaestusMark";
import { memberAiChoiceTitle } from "@/components/practice-vocabulary/data-handling-defs";
import { buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
} from "@/components/ui/empty";
import { CONVERSATION_NOTICE_COPY, type ConversationNotice } from "@/lib/conversation-availability";
import { hasText } from "@/lib/text";

export interface WorkspaceConversationNoticeProps {
	workspaceSlug: string;
	/** The page to come back to after changing the choice; the route reads `useLocation().href`. */
	returnTo: string;
	/** Produced by `conversationNotice`, so `unavailable` always arrives with the saved choice. */
	notice: ConversationNotice;
}

/** The sentences for one notice, with the `unavailable` choice named by its card title. */
function noticeCopy(notice: ConversationNotice): {
	title: string;
	description: ReactNode;
	cta?: string;
} {
	if (notice.reason === "unavailable") {
		const { description, ...copy } = CONVERSATION_NOTICE_COPY.unavailable;
		return {
			...copy,
			description: (
				<>
					{description.before}
					<em>{memberAiChoiceTitle(notice.choice)}</em>
					{description.after}
				</>
			),
		};
	}
	return CONVERSATION_NOTICE_COPY[notice.reason];
}

/**
 * Why Hephaestus is not answering, in Hephaestus's own place. The title is a real `h1` rather than
 * `EmptyTitle` (a `div`): this is the only heading on the fullscreen conversation route.
 */
export function WorkspaceConversationNotice({
	workspaceSlug,
	returnTo,
	notice,
}: WorkspaceConversationNoticeProps) {
	const copy = noticeCopy(notice);
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia>
					<HephaestusMark size={64} pad={2} />
				</EmptyMedia>
				<h1 className="text-sm font-medium tracking-tight">{copy.title}</h1>
				<EmptyDescription>{copy.description}</EmptyDescription>
			</EmptyHeader>
			{hasText(copy.cta) && (
				<EmptyContent>
					{/* A styled Link rather than a Button slot: Base UI's non-native button announces as a
					    button, and this one only navigates. */}
					<Link
						to="/w/$workspaceSlug/onboarding"
						params={{ workspaceSlug }}
						search={{ returnTo }}
						className={buttonVariants()}
					>
						{copy.cta}
					</Link>
				</EmptyContent>
			)}
		</Empty>
	);
}
