import { Link } from "@tanstack/react-router";
import { ClipboardCheckIcon } from "lucide-react";
import type { ReactNode } from "react";

import { PracticeTabsLink, PracticeTabsRail } from "@/components/common/practice-tabs";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";

import { type ReviewScopeSearch, reviewScopeSearch } from "./review-search";

const SECTIONS = [
	{ to: "/w/$workspaceSlug/admin/practices/reviews", label: "Overview", scoped: false },
	{ to: "/w/$workspaceSlug/admin/practices/reviews/runs", label: "Reviews", scoped: false },
	{
		to: "/w/$workspaceSlug/admin/practices/reviews/observations",
		label: "Observations",
		scoped: true,
	},
	{ to: "/w/$workspaceSlug/admin/practices/reviews/feedback", label: "Feedback", scoped: true },
] as const;

export interface PracticeReviewsLayoutProps {
	workspaceSlug: string;
	children: ReactNode;
}

/**
 * Practice reviews: an overview, then the three lists behind it. Every record opens as a level over
 * whichever of them the reader is on, so a list is never left to read what is in it.
 */
export function PracticeReviewsLayout({ workspaceSlug, children }: PracticeReviewsLayoutProps) {
	return (
		<PageLayout>
			<div className="space-y-4">
				<PageHeader
					icon={<ClipboardCheckIcon />}
					title="Practice reviews"
					description="What the reviews need from you, what they did, and what became of their feedback."
				/>
				<PracticeTabsRail>
					<nav
						aria-label="Practice review sections"
						className="relative z-[1] flex flex-wrap gap-x-4 gap-y-1"
					>
						{SECTIONS.map(({ to, label, scoped }) => (
							<PracticeTabsLink
								key={to}
								render={
									<Link
										to={to}
										params={{ workspaceSlug }}
										// Observations and Feedback keep each other's narrowing — one review, one piece
										// of work, a date range — so what a review said and what became of it are one
										// switch apart.
										search={(previous: ReviewScopeSearch) =>
											scoped ? reviewScopeSearch(previous) : {}
										}
										activeOptions={{ exact: true, includeSearch: false }}
									/>
								}
							>
								{label}
							</PracticeTabsLink>
						))}
					</nav>
				</PracticeTabsRail>
			</div>
			{children}
		</PageLayout>
	);
}
