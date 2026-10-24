import type { ReviewRef } from "@/api/types.gen";
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

/** Positions require every review of an item; partial pages cannot establish them. */
export function reviewPositionsOnItem(reviews: readonly ReviewRef[]): Map<string, number> {
	const remaining = new Map<string, number>();
	for (const review of reviews) {
		remaining.set(review.item.id, (remaining.get(review.item.id) ?? 0) + 1);
	}
	const positions = new Map<string, number>();
	for (const review of reviews) {
		const position = remaining.get(review.item.id) ?? 0;
		positions.set(review.id, position);
		remaining.set(review.item.id, position - 1);
	}
	return positions;
}

/** How many reviews each review's work has, by review id; like positions, it needs every review. */
export function reviewTotalsOnItem(reviews: readonly ReviewRef[]): Map<string, number> {
	const perItem = new Map<string, number>();
	for (const review of reviews) {
		perItem.set(review.item.id, (perItem.get(review.item.id) ?? 0) + 1);
	}
	return new Map(reviews.map((review) => [review.id, perItem.get(review.item.id) ?? 0]));
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
