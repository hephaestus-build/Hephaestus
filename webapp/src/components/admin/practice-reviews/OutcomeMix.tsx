import type { ReactNode } from "react";

import { cn } from "cn";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { Skeleton } from "@/components/ui/skeleton";

import type { OutcomeSlot } from "./review-outcomes";
import { ReviewListLink } from "./ReviewListLink";

interface OutcomeLegendProps {
	workspaceSlug: string;
	slots: readonly OutcomeSlot[];
	/** What the counts are of, as the list's accessible name: "Feedback by delivery". */
	"aria-label": string;
}

/**
 * Each count with its registry's icon and word, opening the rows it counts; a count with no list to
 * open is words. Zeroes are left out — a legend of noughts hides the numbers that matter.
 */
function OutcomeLegend({ workspaceSlug, slots, ...props }: OutcomeLegendProps) {
	const shown = slots.filter((slot) => slot.count > 0);
	if (shown.length === 0) {
		return null;
	}
	return (
		<ul
			className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground [&_svg]:size-3.5"
			{...props}
		>
			{shown.map((slot) => {
				const Icon = slot.def.icon;
				const content = (
					<>
						<span className="font-semibold text-foreground tabular-nums">{slot.count}</span>{" "}
						{slot.label}
					</>
				);
				return (
					<li key={slot.key} className="inline-flex items-center gap-1.5">
						<Icon aria-hidden className={cn("shrink-0", statusToneClass(slot.def.badgeVariant))} />
						{slot.target ? (
							<InlineLink
								tone="count"
								render={<ReviewListLink workspaceSlug={workspaceSlug} destination={slot.target} />}
							>
								{content}
							</InlineLink>
						) : (
							<span>{content}</span>
						)}
					</li>
				);
			})}
		</ul>
	);
}

export interface OutcomeMixProps {
	workspaceSlug: string;
	/** Everything the figure counts, which may be more than the legend splits it into. */
	total: number;
	slots: readonly OutcomeSlot[];
	/**
	 * Counts that overlap the parts — a positive outcome marked incorrect is still a positive
	 * outcome — listed apart, under their own name, and never added to the total.
	 */
	flags?: { label: string; slots: readonly OutcomeSlot[] };
	/** The total's noun for its count: "observations", "pieces of feedback". */
	noun: (total: number) => string;
	/** The legend's accessible name. */
	label: string;
	/**
	 * The total set against the period before: "4 more than the previous 30 days". `null` while that
	 * period loads, which holds the line so the figure does not move when it lands.
	 */
	delta?: string | null;
	/** Drawn between the total and the legend: what is still running, the total over time. */
	children?: ReactNode;
}

/** A total, how it changed, then what it splits into. */
export function OutcomeMix({
	workspaceSlug,
	total,
	slots,
	flags,
	noun,
	label,
	delta,
	children,
}: OutcomeMixProps) {
	return (
		<div className="flex flex-col gap-3">
			<div className="space-y-1">
				<p className="flex items-baseline gap-1.5">
					<span
						className={cn(
							"text-2xl leading-none font-semibold tabular-nums",
							total === 0 && "text-muted-foreground",
						)}
					>
						{total}
					</span>
					<span className="text-sm text-muted-foreground">{noun(total)}</span>
				</p>
				{delta === null && <Skeleton className="h-4 w-40" />}
				{typeof delta === "string" && <p className="text-xs text-muted-foreground">{delta}</p>}
			</div>
			{children}
			<OutcomeLegend workspaceSlug={workspaceSlug} slots={slots} aria-label={label} />
			{flags && (
				<OutcomeLegend workspaceSlug={workspaceSlug} slots={flags.slots} aria-label={flags.label} />
			)}
		</div>
	);
}
