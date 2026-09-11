import { Link } from "@tanstack/react-router";

import { HephIcon } from "@/components/brand/HephIcon";
import { buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
} from "@/components/ui/empty";
import { mentorNoticeCopy, type MentorNotice } from "@/lib/mentor-preference";

export interface WorkspaceMentorPreferenceNoticeProps {
	workspaceSlug: string;
	/** The page to come back to after changing the choice; the route reads `useLocation().href`. */
	returnTo: string;
	/** Produced by `mentorPreferenceReason`, so `unavailable` always arrives with its location. */
	notice: MentorNotice;
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
	const copy = mentorNoticeCopy(notice);
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
