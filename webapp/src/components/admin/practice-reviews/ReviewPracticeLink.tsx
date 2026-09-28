import { cn } from "cn";
import type { Practice, ReviewPracticeGroup } from "@/api/types.gen";
import { PracticeDetailHoverCard } from "@/components/admin/practice-editor/PracticeDetailHoverCard";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";

import { practiceLevel } from "./review-levels";

export interface ReviewPracticeLinkProps {
	practiceSlug: string;
	practiceName: string;
	group: ReviewPracticeGroup | undefined;
	practice?: Practice;
	className?: string;
}

/**
 * Opens the practice's level over the current one rather than Practice setup: the reader is asking
 * how the practice behaves, and Practice setup is one press from that level when the answer is
 * "change it".
 */
export function ReviewPracticeLink({
	practiceSlug,
	practiceName,
	group,
	practice,
	className,
}: ReviewPracticeLinkProps) {
	// `relative` lifts this above the stretched title link of `ReviewRow`, which otherwise covers the
	// whole row and would swallow the click.
	const link = (
		<DetailStackLink
			entry={practiceLevel(practiceSlug)}
			className={cn(
				"relative inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-md hover:underline",
				className,
			)}
		>
			<PracticeGroupMark group={group} />
			<span className="min-w-0 break-words">{practiceName}</span>
		</DetailStackLink>
	);

	return practice ? (
		<PracticeDetailHoverCard practice={practice}>{link}</PracticeDetailHoverCard>
	) : (
		link
	);
}

function PracticeGroupMark({ group }: { group: ReviewPracticeGroup | undefined }) {
	if (!group) {
		return null;
	}
	return (
		<GroupPill
			size="sm"
			slug={group.slug}
			name={group.name}
			icon={group.icon}
			color={group.color}
			srLabel
		/>
	);
}
