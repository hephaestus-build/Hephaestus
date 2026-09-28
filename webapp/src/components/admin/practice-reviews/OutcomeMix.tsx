import { cva, type VariantProps } from "class-variance-authority";
import type { ReactNode } from "react";

import { cn } from "cn";
import { InlineLink } from "@/components/common/InlineLink";
import { statusFillClass, statusToneClass } from "@/components/common/status-def";

import { type OutcomeSlot, slotsTotal } from "./review-outcomes";
import { ReviewListLink } from "./ReviewListLink";

export interface OutcomeBarProps {
	slots: readonly OutcomeSlot[];
	className?: string;
}

/**
 * A mix as one bar, each part as wide as its share, in its registry's colour. Decorative: the legend
 * beside it says the same counts in words and icons, so colour is never the only carrier.
 */
export function OutcomeBar({ slots, className }: OutcomeBarProps) {
	const total = slotsTotal(slots);
	return (
		<div
			aria-hidden
			className={cn("flex h-2 w-full gap-px overflow-hidden rounded-full bg-muted", className)}
		>
			{total > 0 &&
				slots
					.filter((slot) => slot.count > 0)
					.map((slot) => (
						<span
							key={slot.key}
							className={cn(
								"h-full w-(--share) first:rounded-l-full last:rounded-r-full",
								statusFillClass(slot.def.badgeVariant),
							)}
							style={{ "--share": `${(slot.count / total) * 100}%` }}
						/>
					))}
		</div>
	);
}

const outcomeLegendVariants = cva("flex flex-wrap text-muted-foreground", {
	variants: {
		size: {
			/** A table cell beside a small bar. */
			sm: "gap-x-3 gap-y-0.5 text-xs [&_svg]:size-3",
			/** A tile or a level. */
			md: "gap-x-4 gap-y-1 text-sm [&_svg]:size-3.5",
		},
	},
	defaultVariants: { size: "md" },
});

export interface OutcomeLegendProps extends VariantProps<typeof outcomeLegendVariants> {
	workspaceSlug: string;
	slots: readonly OutcomeSlot[];
	/** What the counts are of, as the list's accessible name: "Feedback by delivery state". */
	"aria-label": string;
	className?: string;
}

/**
 * The counts a bar draws, in the bar's order: icon, number, the registry's word, each opening the
 * rows it counts. Zeroes are left out — a legend of ten noughts hides the two numbers that matter.
 */
export function OutcomeLegend({
	workspaceSlug,
	slots,
	size,
	className,
	...props
}: OutcomeLegendProps) {
	const shown = slots.filter((slot) => slot.count > 0);
	if (shown.length === 0) {
		return null;
	}
	return (
		<ul className={cn(outcomeLegendVariants({ size }), className)} {...props}>
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
								className="text-muted-foreground"
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
	slots: readonly OutcomeSlot[];
	/** Counts that overlap the parts, listed after them and never drawn in the bar. */
	flags?: readonly OutcomeSlot[];
	/** The total's noun for its count: "observations", "pieces of feedback". */
	noun: (total: number) => string;
	/** The legend's accessible name. */
	label: string;
	/** Drawn between the total and the bar: the total over time, where there is one. */
	children?: ReactNode;
}

const NO_FLAGS: readonly OutcomeSlot[] = [];

/** A total, then how it splits: the bar and its legend. */
export function OutcomeMix({
	workspaceSlug,
	slots,
	flags = NO_FLAGS,
	noun,
	label,
	children,
}: OutcomeMixProps) {
	const total = slotsTotal(slots);
	return (
		<div className="flex flex-col gap-3">
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
			{total > 0 && children}
			{total > 0 && <OutcomeBar slots={slots} />}
			<OutcomeLegend
				workspaceSlug={workspaceSlug}
				slots={[...slots, ...flags]}
				aria-label={label}
			/>
		</div>
	);
}
