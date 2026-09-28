import { cn } from "cn";
import type { ReviewSubject } from "@/api/types.gen";
import { MemberAvatar } from "@/components/activity/MemberAvatar";
import { hasText } from "@/lib/text";

import { subjectLabel } from "./review-format";

export interface ReviewPersonProps {
	person: ReviewSubject | undefined;
	/**
	 * Says which person this is when a surface shows two — "To" and "About" on a piece of feedback
	 * whose recipient is not its subject. Omitted everywhere only one person is in play, where a
	 * prefix would be reading out the column header.
	 */
	prefix?: string;
	className?: string;
}

/** One person as Activity names a member: their face, then their name in plain text. */
export function ReviewPerson({ person, prefix, className }: ReviewPersonProps) {
	return (
		<span className={cn("inline-flex max-w-full min-w-0 items-center gap-1.5 text-xs", className)}>
			{/* A person the review could not name still gets the placeholder face rather than a gap. */}
			<MemberAvatar
				size="sm"
				user={{
					avatarUrl: person?.avatarUrl ?? "",
					name: person?.name ?? "",
					login: person?.login ?? "",
				}}
			/>
			<span className="min-w-0 break-words">
				{hasText(prefix) && <span className="text-muted-foreground">{prefix} </span>}
				{subjectLabel(person)}
			</span>
		</span>
	);
}
