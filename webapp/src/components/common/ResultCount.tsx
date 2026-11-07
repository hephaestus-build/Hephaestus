import { formatNumber } from "@/lib/numbers";

export interface ResultCountProps {
	total: number | undefined;
	/** Singular and plural, in that order: `["result", "results"]`. */
	noun: readonly [string, string];
	hasFilter?: boolean;
}

/**
 * How many rows are below, and whether that is the whole set or what survived the filters. The verb
 * agrees with the count as well as the noun — every list here can be narrowed to one row.
 */
export function ResultCount({ total, noun, hasFilter = false }: ResultCountProps) {
	if (total === undefined) {
		return null;
	}
	const one = total === 1;
	let suffix = "";
	if (hasFilter) {
		suffix = one ? " matches your filters" : " match your filters";
	}
	const text = `${formatNumber(total)} ${one ? noun[0] : noun[1]}${suffix}.`;
	return (
		<p role="status" aria-live="polite" className="text-sm text-muted-foreground">
			{text}
		</p>
	);
}
