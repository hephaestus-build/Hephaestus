import type { ReactElement } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import { Alert, AlertDescription } from "@/components/ui/alert";

import { type TeamThreadData, versionGoneText } from "./team-threads";

export interface TeamThreadHeaderProps {
	thread: TeamThreadData;
	teamName: string;
	/** Opens the reviewed version, read-only. Only for a review whose version is available. */
	reviewedVersionLink?: ReactElement;
	/** Hephaestus does not mentor this team now. The cause stays on the record and is never shown. */
	mentorPaused?: boolean;
}

/**
 * The top of a review's discussion: which version it reviews, who reads it, and what has changed since it began. A
 * review whose version is gone says so, and the messages that cited it stay hidden.
 */
export function TeamThreadHeader({
	thread,
	teamName,
	reviewedVersionLink,
	mentorPaused = false,
}: TeamThreadHeaderProps) {
	const gone = versionGoneText(thread.review.versionSequence, thread.review.versionState);
	return (
		<header className="flex flex-col gap-2">
			<h2 className="text-lg font-semibold" dir="auto">
				{thread.title}
			</h2>
			{gone === undefined && (
				<p className="text-sm text-muted-foreground">
					Review of <bdi>{thread.review.itemTitle}</bdi>, version {thread.review.versionSequence}
					{reviewedVersionLink !== undefined && (
						<>
							{" · "}
							<InlineLink render={reviewedVersionLink}>Open the reviewed version</InlineLink>
						</>
					)}
				</p>
			)}
			<p className="text-sm text-muted-foreground">
				The current members of Team <bdi>{teamName}</bdi> read this review discussion. You see the
				messages from the time you joined.
			</p>
			{gone !== undefined && (
				<Alert>
					<AlertDescription>
						{gone}. Its content is deleted, and messages that cited it are hidden.
					</AlertDescription>
				</Alert>
			)}
			{mentorPaused && (
				<Alert>
					<AlertDescription>
						Hephaestus is not mentoring this team now. Reviews show their observations.
					</AlertDescription>
				</Alert>
			)}
		</header>
	);
}
