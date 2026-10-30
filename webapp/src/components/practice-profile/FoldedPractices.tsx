import { ChevronRightIcon } from "lucide-react";
import { type ReactNode, useId, useState } from "react";

import { cn } from "cn";
import { StatusBadge } from "@/components/common/StatusBadge";
import { OPEN_ROW_BAR } from "@/components/practice-vocabulary/PracticeTable";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { TableCell, TableRow } from "@/components/ui/table";

import { NO_STANDING_YET, NO_STANDING_YET_DEF } from "./practice-folds";

export interface FoldedPracticesProps {
	/** How many practices or groups the fold holds; with none, it draws nothing. */
	count: number;
	/** What the fold holds: the practices of a group, or whole groups. */
	noun: "practice" | "group";
	/** The table's column count, from `PracticeTable`'s `sections`; the toggle row spans them. */
	columns: number;
	/** Open at first; a story shows the table this way. */
	defaultOpen?: boolean;
	/**
	 * The folded practices or groups as rows of the table above, each in its plain state and
	 * opening as any row does.
	 */
	children: ReactNode;
}

/** The words of the fold: its line, which counts nothing, and the sentence under it once open. */
export function foldWords(noun: "practice" | "group") {
	return noun === "practice"
		? { label: NO_STANDING_YET.practiceLabel, description: NO_STANDING_YET.practiceDescription }
		: { label: NO_STANDING_YET.groupLabel, description: NO_STANDING_YET.groupDescription };
}

/**
 * The practices, or the groups, with no standing yet, folded into one row at the end of their
 * table. Whether every review found they did not apply to the reader's work or no review has
 * reviewed them yet, they share the fold, and it does not read as a gap.
 *
 * The fold is two `<tbody>` sections of the table above, passed through `PracticeTable`'s
 * `sections`: a toggle row spanning every column, then the folded rows. Being the same table, the
 * folded rows share its columns and widths, on a phone's stacked layout too; there is no second
 * header and no second frame. A folded group or practice still opens.
 */
export function FoldedPractices({
	count,
	noun,
	columns,
	defaultOpen = false,
	children,
}: FoldedPracticesProps) {
	const [open, setOpen] = useState(defaultOpen);
	const rowsId = useId();
	const descriptionId = useId();
	if (count === 0) {
		return null;
	}
	const { label, description } = foldWords(noun);
	const toggle = () => setOpen((current) => !current);
	return (
		<>
			{/* A line between the rows above and the fold. Closed, the toggle is the table's last row and
			    the frame draws the edge under it. */}
			<tbody data-fold="no-standing-yet" className={cn("border-t", !open && "[&_tr]:border-0")}>
				<TableRow variant="static">
					<TableCell colSpan={columns} className="p-0 whitespace-normal">
						{/* oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- The button inside is the keyboard path and the name; the line only widens a pointer's target to the whole row. */}
						<div
							className="flex cursor-pointer items-center gap-2 pr-2 transition-colors hover:bg-muted/50 @max-[38rem]:pr-0"
							onClick={(event) => {
								if (!(event.target instanceof Element) || event.target.closest("button") === null) {
									toggle();
								}
							}}
						>
							<button
								type="button"
								aria-expanded={open}
								aria-controls={rowsId}
								aria-describedby={open ? descriptionId : undefined}
								onClick={toggle}
								className={cn(
									"flex min-h-10 flex-1 cursor-pointer items-center gap-2 rounded-sm px-2 text-left text-sm font-medium",
									"outline-none focus-visible:ring-2 focus-visible:ring-highlight focus-visible:ring-inset",
									"@max-[38rem]:min-h-8 @max-[38rem]:px-0",
								)}
							>
								<ChevronRightIcon
									aria-hidden
									className={cn(
										"size-4 shrink-0 text-muted-foreground transition-transform",
										open && "rotate-90",
									)}
								/>
								{label}
							</button>
						</div>
						{open && (
							<p
								id={descriptionId}
								className="max-w-2xl px-2 pb-2.5 text-sm text-muted-foreground @max-[38rem]:px-0"
							>
								{description}
							</p>
						)}
					</TableCell>
				</TableRow>
			</tbody>
			<tbody id={rowsId} aria-label={label} hidden={!open} className="[&_tr:last-child]:border-0">
				{open && children}
			</tbody>
		</>
	);
}

export interface FoldedStandingCellProps {
	/** Why the row has no standing yet, as `practiceNoStandingYetReason` or `groupNoStandingYetReason` words it. */
	reason: string;
}

/**
 * The standing of a folded row: the "No standing yet" badge alone. The fold already says why the
 * row is there, so the row does not repeat it with a trend note, a ring and counts; the badge's
 * sentence says this row's own reason.
 */
export function FoldedStandingCell({ reason }: FoldedStandingCellProps) {
	return (
		<TableCell className={cn(OPEN_ROW_BAR, "whitespace-normal")}>
			<NoStandingYetBadge reason={reason} />
		</TableCell>
	);
}

/**
 * "No standing yet", with why behind it on hover, focus and tap. The same trigger as StandingBadge's,
 * so a row treats a press on it the same way.
 */
export function NoStandingYetBadge({ reason }: { reason: string }) {
	return (
		<StatusTooltip
			openOnTap
			def={{ ...NO_STANDING_YET_DEF, description: reason }}
			render={
				<StatusBadge
					def={NO_STANDING_YET_DEF}
					render={<button type="button" />}
					data-tooltip-only=""
					className="cursor-help"
				/>
			}
		/>
	);
}
