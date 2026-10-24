import { Link } from "@tanstack/react-router";
import { ClipboardCheckIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import { PracticeTabsLink, PracticeTabsRail } from "@/components/common/practice-tabs";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";

import { GlobalScopeSlot } from "./GlobalScopeRow";
import { carriedScope, type ScopeSource } from "./review-scope";

const SECTIONS = [
	{ to: "/w/$workspaceSlug/admin/practices/reviews/overview", label: "Overview", tab: "overview" },
	{ to: "/w/$workspaceSlug/admin/practices/reviews", label: "Reviews", tab: "reviews" },
	{
		to: "/w/$workspaceSlug/admin/practices/reviews/results",
		label: "Observations",
		tab: "results",
	},
	{
		to: "/w/$workspaceSlug/admin/practices/reviews/problems",
		label: "Problems",
		tab: "problems",
	},
	{ to: "/w/$workspaceSlug/admin/practices/reviews/feedback", label: "Feedback", tab: "feedback" },
] as const;

/** How many each list tab holds under the shared scope; a tab whose count is not known yet is left out. */
export type ReviewTabCounts = Partial<
	Record<"reviews" | "results" | "problems" | "feedback", number>
>;

const COUNT_FORMAT = new Intl.NumberFormat("en-US");

const NO_COUNTS: ReviewTabCounts = {};

export interface PracticeReviewsLayoutProps {
	workspaceSlug: string;
	/**
	 * The count behind each list tab's name, under the shared scope. The overview has none: it is not
	 * a list.
	 */
	counts?: ReviewTabCounts;
	/**
	 * The record tracked from a row (`ReviewSelectionBar`). It goes in the row above the tabs, after
	 * the scope, because it narrows every tab.
	 */
	selection?: ReactNode;
	children: ReactNode;
}

export function PracticeReviewsLayout({
	workspaceSlug,
	counts = NO_COUNTS,
	selection,
	children,
}: PracticeReviewsLayoutProps) {
	const [scopeElement, setScopeElement] = useState<HTMLDivElement | null>(null);
	return (
		<PageLayout>
			<GlobalScopeSlot element={scopeElement} selection={selection}>
				<div className="space-y-4">
					<PageHeader
						icon={<ClipboardCheckIcon />}
						title="Practice reviews"
						description="What the reviews need from you, what they did, and what became of their feedback."
					/>
					<div ref={setScopeElement} className="empty:hidden" />
					<PracticeTabsRail>
						<nav
							aria-label="Practice review sections"
							className="relative z-[1] flex flex-wrap gap-x-4 gap-y-1"
						>
							{SECTIONS.map(({ to, label, tab }) => {
								const count = tab === "overview" ? undefined : counts[tab];
								const shown = count === undefined ? undefined : COUNT_FORMAT.format(count);
								return (
									<PracticeTabsLink
										key={to}
										// "Reviews, 1,688": the comma keeps a screen reader from reading one number.
										aria-label={shown === undefined ? undefined : `${label}, ${shown}`}
										render={
											<Link
												to={to}
												params={{ workspaceSlug }}
												// Every tab keeps the shared scope: a person, practices, a date range and the
												// selection. A tab's own facets stay behind.
												search={(previous: ScopeSource) => carriedScope(previous)}
												activeOptions={{ exact: true, includeSearch: false }}
											/>
										}
									>
										{label}
										{shown !== undefined && (
											<span aria-hidden className="font-normal text-muted-foreground tabular-nums">
												{shown}
											</span>
										)}
									</PracticeTabsLink>
								);
							})}
						</nav>
					</PracticeTabsRail>
				</div>
				{children}
			</GlobalScopeSlot>
		</PageLayout>
	);
}
