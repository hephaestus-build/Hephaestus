import { formatWeekdayDay } from "@/lib/dates";

export interface DayGroup<TRow> {
	label: string;
	rows: TRow[];
}

/** Name days explicitly; a page left open across midnight must not retain a stale Today label. */
export function groupByDay<TRow>(
	rows: readonly TRow[],
	dateOf: (row: TRow) => Date,
	now: Date,
): DayGroup<TRow>[] {
	const days: DayGroup<TRow>[] = [];
	for (const row of rows) {
		const label = formatWeekdayDay(dateOf(row), now);
		const last = days.at(-1);
		if (last?.label === label) {
			last.rows.push(row);
		} else {
			days.push({ label, rows: [row] });
		}
	}
	return days;
}

/**
 * "Review 2 of 3": a review's place among the runs of its work. With the total known, every run of
 * a work reviewed more than once says it, the first too; without it, only a later run does.
 */
export function reviewOrdinalLabel(position: number, total?: number): string | undefined {
	if (total !== undefined && total > 1 && total >= position) {
		return `Review ${position} of ${total}`;
	}
	return position > 1 ? `Review ${position}` : undefined;
}
