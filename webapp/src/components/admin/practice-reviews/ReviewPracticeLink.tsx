import { Link, useParams } from "@tanstack/react-router";

import { cn } from "cn";
import type { GroupRef, Practice } from "@/api/types.gen";
import { WorkspacePracticeDetailHoverCard } from "@/components/admin/practice-editor/WorkspacePracticeDetailHoverCard";
import { InlineLink } from "@/components/common/InlineLink";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";

export interface ReviewPracticeLinkProps {
	practiceSlug: string;
	practiceName: string;
	group: GroupRef | null;
	practice?: Practice;
	className?: string;
}

// This link opens practice results; editing the definition belongs to the practice sheet.
export function ReviewPracticeLink({
	practiceSlug,
	practiceName,
	group,
	practice,
	className,
}: ReviewPracticeLinkProps) {
	const workspaceSlug = useParams({ strict: false, select: (params) => params.workspaceSlug });
	// `relative` lifts this above the stretched title link of `ReviewRow`, which otherwise covers the
	// whole row and would swallow the click.
	const link = (
		<InlineLink
			render={
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/results"
					params={{ workspaceSlug: workspaceSlug ?? "" }}
					search={{ practiceSlug: [practiceSlug] }}
				/>
			}
			className={cn("relative inline-flex max-w-full min-w-0 items-center gap-1.5", className)}
		>
			<PracticeGroupMark group={group} />
			<span className="min-w-0 break-words">{practiceName}</span>
		</InlineLink>
	);

	return practice ? (
		<WorkspacePracticeDetailHoverCard practice={practice}>{link}</WorkspacePracticeDetailHoverCard>
	) : (
		link
	);
}

export interface ReviewPracticeNameProps {
	practiceName: string;
	group: GroupRef | null;
	className?: string;
}

/**
 * The practice by its group's mark and its name, as plain text. A row names its practice this way:
 * the row's title is its one link, and the record's level links the practice.
 */
export function ReviewPracticeName({ practiceName, group, className }: ReviewPracticeNameProps) {
	return (
		<span className={cn("inline-flex max-w-full min-w-0 items-center gap-1.5", className)}>
			<PracticeGroupMark group={group} />
			<span className="min-w-0 break-words">{practiceName}</span>
		</span>
	);
}

function PracticeGroupMark({ group }: { group: GroupRef | null }) {
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
