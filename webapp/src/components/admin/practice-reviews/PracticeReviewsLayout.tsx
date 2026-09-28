import { Link } from "@tanstack/react-router";
import { ClipboardCheckIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { tabsListVariants } from "@/components/ui/tabs";

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

/**
 * These are router links carrying `aria-current="page"`, not tabs — a nav that changes the URL must not
 * claim `role="tab"` — so `TabsTrigger` itself cannot be reused. The track below does reuse the exported
 * `tabsListVariants`, and this is that component's own recipe with `data-active` swapped for
 * `aria-[current=page]` and the icon/line-variant selectors dropped, so the two surfaces stay one idiom
 * rather than two hand-tuned lookalikes. If `ui/tabs.tsx` ever exports a trigger variant, use it here.
 */
const SECTION_LINK_CLASS =
	"relative inline-flex h-[calc(100%-1px)] flex-1 items-center justify-center rounded-md border border-transparent px-1.5 py-0.5 text-sm font-medium whitespace-nowrap text-foreground/60 transition-all hover:text-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-1 focus-visible:outline-ring dark:text-muted-foreground dark:hover:text-foreground aria-[current=page]:bg-background aria-[current=page]:text-foreground aria-[current=page]:shadow-sm dark:aria-[current=page]:border-input dark:aria-[current=page]:bg-input/30 dark:aria-[current=page]:text-foreground";

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
				<nav
					aria-label="Practice review sections"
					className={cn(tabsListVariants(), "h-8 w-full sm:w-fit")}
				>
					{SECTIONS.map(({ to, label, scoped }) => (
						<Link
							key={to}
							to={to}
							params={{ workspaceSlug }}
							// Observations and Feedback keep each other's narrowing — one review, one piece of
							// work, a date range — so what a review said and what became of it are one switch apart.
							search={(previous: ReviewScopeSearch) => (scoped ? reviewScopeSearch(previous) : {})}
							activeOptions={{ exact: true, includeSearch: false }}
							className={SECTION_LINK_CLASS}
						>
							{label}
						</Link>
					))}
				</nav>
			</div>
			{children}
		</PageLayout>
	);
}
