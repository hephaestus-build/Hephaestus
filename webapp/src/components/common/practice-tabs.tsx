import { mergeProps } from "@base-ui/react/merge-props";
import { useRender } from "@base-ui/react/use-render";
import type { ComponentProps } from "react";

import { cn } from "cn";
import { FOCUS_RING } from "@/components/common/focus";
import { Skeleton } from "@/components/ui/skeleton";
import { TabsList, TabsTrigger } from "@/components/ui/tabs";

/**
 * What a practice line tab changes about the primitive's line tab — its size, its padding and the
 * bar's offset onto the rail — shared by the trigger and the link so the two cannot come apart. The
 * offset is spelled twice: bare for the link, which sits in no tabs root, and under the primitive's
 * own orientation variant for the trigger, where it replaces the primitive's offset.
 */
const LINE_TAB_SHAPE = cn(
	"h-auto flex-none gap-1.5 px-0 pt-0 pb-2 text-sm",
	"after:bottom-[-0.5px] group-data-[orientation=horizontal]/tabs:after:bottom-[-0.5px]",
);

/**
 * The primitive's line-variant look (`ui/tabs.tsx`, which exports no class to reuse), restated for
 * an element that is not a `TabsTrigger`: muted at rest, the current one strong with a 2 px bar
 * over the rail, current by the router's `aria-current="page"` rather than Base UI's `data-active`.
 */
const LINE_TAB_LINK = cn(
	"relative inline-flex items-center rounded-sm font-medium whitespace-nowrap text-foreground/60 transition-colors hover:text-foreground dark:text-muted-foreground dark:hover:text-foreground",
	"after:absolute after:inset-x-0 after:h-0.5 after:bg-foreground after:opacity-0 after:transition-opacity",
	"aria-[current=page]:font-semibold aria-[current=page]:text-foreground aria-[current=page]:after:opacity-100",
);

/**
 * The tab row the practice surfaces draw over what they switch between. A hairline rail is drawn on the row itself, at its
 * bottom edge, under the tabs and whatever sits beside them; the tabs sit on that same edge, so
 * the active tab's 2 px bar covers the rail rather than hanging under it. The row wraps at 320px,
 * where labelled tabs with their counts outrun the line.
 */
export function PracticeTabsRail({ className, ...props }: ComponentProps<"div">) {
	return (
		<div
			className={cn(
				"relative flex flex-wrap items-end justify-between gap-x-6 gap-y-2 after:pointer-events-none after:absolute after:inset-x-0 after:bottom-0 after:h-px after:bg-border",
				className,
			)}
			{...props}
		/>
	);
}

export function PracticeTabsList({ className, ...props }: ComponentProps<typeof TabsList>) {
	return (
		<TabsList
			variant="line"
			// `h-auto` restated under the orientation variant, which otherwise wins with its `h-8` and
			// lets a wrapped second row of tabs spill over the content.
			className={cn(
				"relative z-[1] h-auto flex-wrap justify-start gap-x-4 gap-y-1 p-0 group-data-[orientation=horizontal]/tabs:h-auto",
				className,
			)}
			{...props}
		/>
	);
}

export interface PracticeTabsTriggerProps extends ComponentProps<typeof TabsTrigger> {
	/** How many the tab lists; left out while that is not yet known. */
	count?: number;
}

export function PracticeTabsTrigger({
	count,
	className,
	children,
	...props
}: PracticeTabsTriggerProps) {
	return (
		<TabsTrigger className={cn(LINE_TAB_SHAPE, "data-active:font-semibold", className)} {...props}>
			{children}
			{count !== undefined && (
				// The space is the tab's accessible name: "Observations 3", not "Observations3".
				<>
					{" "}
					<span className="font-normal text-muted-foreground tabular-nums">{count}</span>
				</>
			)}
		</TabsTrigger>
	);
}

type PracticeTabsLinkProps = useRender.ComponentProps<"a">;

/**
 * A line tab that is a link, for a row of sections that change the URL: a nav must not claim
 * `role="tab"`, so it cannot be a `PracticeTabsTrigger`, but it takes the trigger's shape and the
 * primitive's look. Pass the router link as `render`; it marks the current section with
 * `aria-current="page"`.
 */
export function PracticeTabsLink({ className, render, ...props }: PracticeTabsLinkProps) {
	return useRender({
		defaultTagName: "a",
		render,
		props: mergeProps<"a">(
			{ className: cn(LINE_TAB_SHAPE, LINE_TAB_LINK, FOCUS_RING, className) },
			props,
		),
	});
}

/** The rail with tab-shaped blanks on it, while what the tabs will count is still loading. */
export function PracticeTabsSkeleton({ tabs = 3 }: { tabs?: number }) {
	return (
		<PracticeTabsRail aria-hidden>
			<div className="flex gap-x-4 pb-2">
				{Array.from({ length: tabs }, (_, index) => (
					<Skeleton key={index} className="h-5 w-24" />
				))}
			</div>
		</PracticeTabsRail>
	);
}
