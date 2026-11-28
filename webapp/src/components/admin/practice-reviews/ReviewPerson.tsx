import { cn } from "cn";
import type { ReviewPerson as ReviewPersonData } from "@/api/types.gen";
import { MemberAvatar } from "@/components/activity/MemberAvatar";
import { hasText } from "@/lib/text";

import { developerLabel } from "./review-format";

export interface ReviewPersonProps {
	person: ReviewPersonData | null;
	/**
	 * Says which person this is when a surface shows two people. Omitted everywhere only one person is in play, where a
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
					name: person?.displayName ?? "",
					login: person?.providerLogin ?? "",
				}}
			/>
			<span className="min-w-0 break-words">
				{hasText(prefix) && <span className="text-muted-foreground">{prefix} </span>}
				{developerLabel(person)}
			</span>
		</span>
	);
}

/**
 * Who a piece of feedback is for. Only feedback on the work names its developer, as the work does:
 * feedback on a practice page or in a conversation names nobody to staff.
 */
export function FeedbackPerson({ person }: { person: ReviewPersonData | null }) {
	return person === null ? (
		<span className="text-xs text-muted-foreground">Not shown</span>
	) : (
		<ReviewPerson person={person} />
	);
}
