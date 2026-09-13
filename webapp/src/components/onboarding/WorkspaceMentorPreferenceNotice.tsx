import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { HephIcon } from "@/components/brand/HephIcon";
import { memberAiChoiceTitle } from "@/components/practice-vocabulary/data-handling-defs";
import { buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
} from "@/components/ui/empty";
import { MENTOR_PREFERENCE_COPY, type MentorNotice } from "@/lib/mentor-preference";

export interface WorkspaceMentorPreferenceNoticeProps {
	workspaceSlug: string;
	/** The page to come back to after changing the choice; the route reads `useLocation().href`. */
	returnTo: string;
	/** Produced by `mentorPreferenceReason`, so `unavailable` always arrives with the saved choice. */
	notice: MentorNotice;
}

/** The three sentences for one notice, with the `unavailable` choice named by its card title. */
function noticeCopy(notice: MentorNotice): { title: string; description: ReactNode; cta: string } {
	if (notice.reason === "unavailable") {
		const { description, ...copy } = MENTOR_PREFERENCE_COPY.unavailable;
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
	return MENTOR_PREFERENCE_COPY[notice.reason];
}

/**
 * Why Heph is not answering, in Heph's own place. The title is a real `h1` rather than
 * `EmptyTitle` (a `div`): this is the only heading on the fullscreen mentor route.
 */
export function WorkspaceMentorPreferenceNotice({
	workspaceSlug,
	returnTo,
	notice,
}: WorkspaceMentorPreferenceNoticeProps) {
	const copy = noticeCopy(notice);
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia>
					<HephIcon size={64} pad={2} />
				</EmptyMedia>
				<h1 className="text-sm font-medium tracking-tight">{copy.title}</h1>
				<EmptyDescription>{copy.description}</EmptyDescription>
			</EmptyHeader>
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
		</Empty>
	);
}
