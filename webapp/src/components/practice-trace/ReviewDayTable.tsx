import type { ReactNode } from "react";

import { cn } from "cn";
import type { ItemRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	RecordDayTable,
	type RecordTableColumn,
	RecordTableSkeleton,
} from "@/components/practice-vocabulary/RecordTable";
import { TableCell } from "@/components/ui/table";
import { itemName } from "@/lib/item-kinds";
import { rendersContent } from "@/lib/react-node";

import { outcomeCounts, practicesLabel, type ReviewSaw } from "./review-saw";
import { ReviewOrdinalTag } from "./review-tags";

export type ReviewColumn = RecordTableColumn;

function leadColumns(timeLabel: string): RecordTableColumn[] {
	return [{ label: timeLabel, className: "w-24 whitespace-nowrap" }, { label: "Work" }];
}

export interface ReviewDayTableProps<TReview> {
	"aria-label": string;
	/**
	 * The head over the review's time, in the word its date filter uses: "Requested" where the time
	 * is when the review was asked for, "Reviewed" where it is when it finished.
	 */
	timeLabel?: string;
	columns: readonly ReviewColumn[];
	reviews: readonly TReview[];
	dateOf: (review: TReview) => Date;
	reviewKey: (review: TReview) => string;
	renderRow: (review: TReview) => ReactNode;
	empty: ReactNode;
	/** Placeholder rows after the reviews while the next page loads. */
	loadingMore?: number;
	/** Classes on the `<table>`, such as a list that centers its cells in the row. */
	tableClassName?: string;
}

export function ReviewDayTable<TReview>({
	timeLabel = "Reviewed",
	columns,
	reviews,
	reviewKey,
	...table
}: ReviewDayTableProps<TReview>) {
	return (
		<RecordDayTable
			{...table}
			columns={[...leadColumns(timeLabel), ...columns]}
			rows={reviews}
			rowKey={reviewKey}
		/>
	);
}

export interface ReviewDayTableSkeletonProps {
	"aria-label": string;
	timeLabel?: string;
	columns: readonly ReviewColumn[];
	rows: number;
}

export function ReviewDayTableSkeleton({
	timeLabel = "Reviewed",
	columns,
	...skeleton
}: ReviewDayTableSkeletonProps) {
	return <RecordTableSkeleton {...skeleton} columns={[...leadColumns(timeLabel), ...columns]} />;
}

export interface ReviewItemCellProps {
	item: ItemRef;
	position?: number;
	total?: number;
	/**
	 * `false` names the item as plain text, for a list whose row opens one thing: the review's
	 * level links the item to its provider.
	 */
	linked?: boolean;
	children?: ReactNode;
	/**
	 * Tags about this review of the work, such as "Latest": on one line with its sequence ("Review
	 * 2"), so the two read together.
	 */
	tags?: ReactNode;
	/** A line under the tags, such as the way to the feedback on the work. */
	footer?: ReactNode;
}

export function ReviewItemCell({
	item,
	position,
	total,
	linked = true,
	children,
	tags,
	footer,
}: ReviewItemCellProps) {
	const ItemIcon = reviewedWorkIcon(item.kind, item.provider ?? undefined);
	const name = itemName(item);
	const title = titleBeside(item, name);
	return (
		<TableCell className="align-top whitespace-normal">
			<span className="flex min-w-0 flex-col items-start gap-1">
				{/* `max-w-full`: in an `items-start` column the row is not stretched, and its fit-content
				    width counts the label whole, so `truncate` would never take effect. */}
				<span className="flex max-w-full min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
					{/* The icon and the label on one line that never wraps, so the label truncates beside
					    its icon; the chips wrap on their own. */}
					<span className="flex max-w-full min-w-0 items-center gap-x-1.5">
						<ItemIcon className="size-3.5 shrink-0 text-muted-foreground" />
						{linked ? (
							<InlineLink href={item.url ?? undefined} external className="truncate font-medium">
								{name}
							</InlineLink>
						) : (
							<span className="truncate font-medium">{name}</span>
						)}
					</span>
					{children}
				</span>
				{title !== undefined && (
					<span className="max-w-xs text-xs text-muted-foreground">{title}</span>
				)}
				<ReviewTags position={position} total={total} tags={tags} />
				{footer}
			</span>
		</TableCell>
	);
}

function ReviewTags({
	position,
	total,
	tags,
}: {
	position?: number;
	total?: number;
	tags?: ReactNode;
}) {
	if (!rendersContent(tags)) {
		return <ReviewOrdinalTag position={position} total={total} />;
	}
	return (
		<span className="flex flex-wrap items-center gap-1.5">
			<ReviewOrdinalTag position={position} total={total} />
			{tags}
		</span>
	);
}

/**
 * What names the item under its kind, without repeating the line above. A provider title often ends
 * in the number the kind line carries ("Make checkout retries safe #25"). A document or thread is
 * named by its kind alone, so its label or title goes here.
 */
function titleBeside(item: ItemRef, name: string): string | undefined {
	const label = item.label.trim();
	const named = label !== "" && name.endsWith(label);
	let title = item.title?.trim() ?? "";
	if (named && title.endsWith(` ${label}`)) {
		title = title.slice(0, -label.length).trim();
	}
	if (title === "") {
		title = named ? "" : label;
	}
	return title === "" || title === name || (named && title === label) ? undefined : title;
}

export interface ReviewSawCellProps {
	saw: ReviewSaw;
}

/**
 * What the review saw, and nothing else: how many practices it reviewed on the first line, then how
 * they came out on the line under it, each outcome with its registry icon and tone and its count
 * ("8 practices", then "5 met · 2 not met").
 */
export function ReviewSawCell({ saw }: ReviewSawCellProps) {
	const outcomes = outcomeCounts(saw);
	return (
		<TableCell className="align-top whitespace-normal">
			<span className="flex min-w-0 flex-col items-start gap-1 text-sm">
				<span className="font-semibold">{practicesLabel(saw)}</span>
				{outcomes.length > 0 && (
					// Each outcome led by its own icon, apart by a gap rather than a dot, so the line can
					// wrap where the table is narrow without a wrapped line starting with a separator.
					<span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
						{outcomes.map(({ outcome, text }) => {
							const def = OUTCOME_DEFS[outcome];
							const Icon = def.icon;
							return (
								<span key={outcome} className="inline-flex items-center gap-1 whitespace-nowrap">
									<Icon
										aria-hidden
										className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))}
									/>
									{text}
								</span>
							);
						})}
					</span>
				)}
			</span>
		</TableCell>
	);
}
