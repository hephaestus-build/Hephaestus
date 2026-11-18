import type { MyProductFeedback } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { formatTime } from "@/lib/dates";

import { holdCountdown } from "./sharing-copy";

export interface PublicHoldNoticeProps {
	state: MyProductFeedback["state"];
	/** The end of the time in which the reporter can keep the report private. */
	filedAfter?: Date;
	/** The reporter allows a public issue. */
	wantsPublic: boolean;
	/** The reporter reads "you"; an admin reads "the reporter". */
	reader: "reporter" | "admin";
}

/**
 * When a report on its way to a public issue files, and what the reporter can do until then. It
 * renders nothing for a report that is not on that way.
 */
export function PublicHoldNotice({
	state,
	filedAfter,
	wantsPublic,
	reader,
}: PublicHoldNoticeProps) {
	const now = useNow();
	if (filedAfter === undefined) {
		return null;
	}
	const reporter = reader === "reporter";
	if (state === "DRAFTING" && wantsPublic) {
		const keeps = reporter ? "you keep it private" : "the reporter keeps it private";
		return (
			<p className="text-muted-foreground">
				{filedAfter.getTime() > now
					? `Once drafted, it can become a public issue at about ${formatTime(filedAfter)} unless ${keeps}.`
					: `Once drafted, it can become a public issue right away unless ${keeps}.`}
			</p>
		);
	}
	if (state === "HELD") {
		return (
			<p className="text-muted-foreground">
				It becomes a public issue at about {formatTime(filedAfter)} (
				{holdCountdown(filedAfter, now)}) unless{" "}
				{reporter ? "you keep it private or edit it" : "the reporter keeps it private or edits it"}.
				A last check can still keep it private.
			</p>
		);
	}
	return null;
}
