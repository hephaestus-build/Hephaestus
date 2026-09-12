import { Link } from "@tanstack/react-router";

import { cn } from "cn";
import type { Practice, ReviewPracticeGroup } from "@/api/types.gen";
import { GroupPill } from "@/components/admin/practice-catalog/GroupPill";
import { PracticeDetailHoverCard } from "@/components/admin/practice-catalog/PracticeDetailHoverCard";

export interface ReviewPracticeLinkProps {
	workspaceSlug: string;
	practiceSlug: string;
	practiceName: string;
	group: ReviewPracticeGroup | undefined;
	practice?: Practice;
	className?: string;
}

export function ReviewPracticeLink({
	workspaceSlug,
	practiceSlug,
	practiceName,
	group,
	practice,
	className,
}: ReviewPracticeLinkProps) {
	// `relative` lifts this above the stretched title link of `ReviewRow`, which otherwise covers the
	// whole row and would swallow the click.
	const link = (
		<Link
			to="/w/$workspaceSlug/admin/practices/$practiceSlug"
			params={{ workspaceSlug, practiceSlug }}
			className={cn(
				"relative inline-flex min-w-0 max-w-full items-center gap-1.5 rounded-md hover:underline",
				className,
			)}
		>
			<PracticeGroupMark group={group} />
			<span className="min-w-0 break-words">{practiceName}</span>
		</Link>
	);

	return practice ? (
		<PracticeDetailHoverCard practice={practice}>{link}</PracticeDetailHoverCard>
	) : (
		link
	);
}

function PracticeGroupMark({ group }: { group: ReviewPracticeGroup | undefined }) {
	if (!group) return null;
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
