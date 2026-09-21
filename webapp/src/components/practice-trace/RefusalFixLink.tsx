import { Link } from "@tanstack/react-router";

import { DEFAULT_REVIEW_SECTION } from "@/components/admin/practices/review/review-sections";

import { REFUSAL_FIXES, type SignalStateReason } from "./trace-format";

export interface RefusalFixLinkProps {
	workspaceSlug: string;
	reason: SignalStateReason;
	/**
	 * Every member of a workspace can open a trace, and a link into `/admin` bounces all but the
	 * admins off the route guard back to the workspace home — losing the page they were reading.
	 */
	canAdminister: boolean;
	className?: string;
}

/**
 * The way out of a refusal, where one exists. Renders nothing at all rather than a disabled or
 * explanatory stand-in when there is no fix or no standing to apply it: the sentence beside it
 * already says everything a member can act on.
 */
export function RefusalFixLink({
	workspaceSlug,
	reason,
	canAdminister,
	className,
}: RefusalFixLinkProps) {
	const fix = REFUSAL_FIXES[reason];
	if (!fix || !canAdminister) {
		return null;
	}
	const linkClass = className ?? "font-medium underline underline-offset-4 hover:no-underline";

	if (fix.section) {
		return (
			<Link
				to="/w/$workspaceSlug/admin/practices/review"
				params={{ workspaceSlug }}
				search={{ section: fix.section === DEFAULT_REVIEW_SECTION ? undefined : fix.section }}
				className={linkClass}
			>
				{fix.label}
			</Link>
		);
	}
	return (
		<Link to={fix.to} params={{ workspaceSlug }} className={linkClass}>
			{fix.label}
		</Link>
	);
}
