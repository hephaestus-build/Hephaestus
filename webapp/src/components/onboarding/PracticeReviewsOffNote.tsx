import type { ReactElement } from "react";

import { InlineLink } from "@/components/common/InlineLink";

export interface PracticeReviewsOffNoteProps {
	workspaceName: string;
	/** The route's link to Workspace settings, where the setup summary lists what is left. */
	settingsLink: ReactElement;
}

/**
 * The line that tells a workspace admin why nobody here gets practice feedback, and where setup goes
 * on. A new workspace starts with practice reviews off and opens on Activity, which says nothing else
 * about it. A workspace may keep them off on purpose, so the line stays for as long as they are off
 * and stays quiet: it asks for nothing.
 */
export function PracticeReviewsOffNote({
	workspaceName,
	settingsLink,
}: PracticeReviewsOffNoteProps) {
	return (
		<p className="text-sm text-muted-foreground">
			Practice reviews are off in {workspaceName}, so members get no practice feedback. To set them
			up, open <InlineLink render={settingsLink}>Workspace settings</InlineLink>.
		</p>
	);
}
