import { Link } from "@tanstack/react-router";

import { InlineLink } from "@/components/common/InlineLink";
import type { ProfileFirstRunProps } from "@/components/practice-profile/ProfileFirstRun";
import { buttonVariants } from "@/components/ui/button";

/**
 * The ways on that the first-run state offers, for the Practice profile and for Get started's review
 * step on any page. Admin pages only for a reader who administers the workspace, so a member page
 * makes no admin read.
 */
export function firstRunLinks({
	workspaceSlug,
	isAdmin,
	returnTo,
}: {
	workspaceSlug: string;
	isAdmin: boolean;
	/** Where the AI choice page leads back to. */
	returnTo: string;
}): Pick<ProfileFirstRunProps, "requestReviewLink" | "aiChoiceLink" | "adminLinks"> {
	return {
		requestReviewLink: (
			<Link
				to="/w/$workspaceSlug/activity"
				params={{ workspaceSlug }}
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				Request a review on Activity
			</Link>
		),
		aiChoiceLink: (label) => (
			<Link
				to="/w/$workspaceSlug/onboarding"
				params={{ workspaceSlug }}
				search={{ returnTo }}
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				{label}
			</Link>
		),
		adminLinks: isAdmin
			? {
					setupSummary: (label) => (
						<InlineLink
							render={<Link to="/w/$workspaceSlug/admin/settings" params={{ workspaceSlug }} />}
						>
							{label}
						</InlineLink>
					),
					reviewScope: (label) => (
						<InlineLink
							render={
								<Link
									to="/w/$workspaceSlug/admin/practices/review"
									params={{ workspaceSlug }}
									search={{ section: "automated-review" }}
								/>
							}
						>
							{label}
						</InlineLink>
					),
				}
			: undefined,
	};
}
