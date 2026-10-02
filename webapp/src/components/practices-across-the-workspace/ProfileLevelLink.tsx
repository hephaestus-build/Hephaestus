import { Link } from "@tanstack/react-router";
import { ArrowRightIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import {
	practiceGroupLevel,
	practiceLevel,
} from "@/components/practice-profile/practice-profile-search";
import { ROW_ACTION_PRESSED } from "@/components/practice-vocabulary/PracticeTable";
import { buttonVariants } from "@/components/ui/button";

export interface ProfileLevelLinkProps {
	workspaceSlug: string;
	groupSlug: string;
	/** Opens the practice over its group; without it the link opens the group. */
	practiceSlug?: string;
	/** `row`: a row's own action, as the reviews list draws one; `level`: a level's head. */
	placement: "row" | "level";
	/** The visible words first, then what tells the rows apart (WCAG 2.2 SC 2.5.3). */
	"aria-label": string;
	children: ReactNode;
}

/**
 * A button that goes to the reader's own level on their Practice profile: the group, or a practice
 * stacked over its group so Back returns to the group first. A route change, never a level on this
 * page: the profile is where the reader's own feedback lives.
 */
export function ProfileLevelLink({
	workspaceSlug,
	groupSlug,
	practiceSlug,
	placement,
	"aria-label": label,
	children,
}: ProfileLevelLinkProps) {
	const levels = [practiceGroupLevel(groupSlug)];
	if (practiceSlug !== undefined) {
		levels.push(practiceLevel(practiceSlug));
	}
	return (
		<Link
			to="/w/$workspaceSlug/practice-profile"
			params={{ workspaceSlug }}
			search={{ detail: levels.map(detailStackKey) }}
			aria-label={label}
			className={cn(
				buttonVariants({ variant: "outline", size: placement === "row" ? "xs" : "default" }),
				placement === "row" && ROW_ACTION_PRESSED,
			)}
		>
			{children}
			<ArrowRightIcon aria-hidden data-icon="inline-end" />
		</Link>
	);
}
