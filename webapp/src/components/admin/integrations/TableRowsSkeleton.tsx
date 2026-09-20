import { Skeleton } from "@/components/ui/skeleton";
import { TableBody, TableCell, TableRow } from "@/components/ui/table";
import { hasText } from "@/lib/text";

export interface TableRowsSkeletonProps {
	/**
	 * One entry per column, in order — a Tailwind width class for a column that holds content, or
	 * `null` for one that doesn't (a trailing action slot has nothing to promise). Length must match
	 * the header's column count, or the placeholder columns won't line up with the real ones.
	 */
	columns: (string | null)[];
	rows?: number;
}

/**
 * Placeholder `<tbody>` rows for a table whose `<thead>` is already mounted, so the skeleton reserves
 * the real column box. A stack of full-width grey bars would promise the wrong shape and shift the
 * layout when the real columns arrive; pairing this with the real header means only the text changes
 * on resolve.
 */
export function TableRowsSkeleton({ columns, rows = 5 }: TableRowsSkeletonProps) {
	return (
		<TableBody>
			{Array.from({ length: rows }, (_, rowIndex) => (
				<TableRow key={rowIndex}>
					{columns.map((width, cellIndex) => (
						<TableCell key={cellIndex}>
							{hasText(width) && <Skeleton className={`h-5 ${width}`} />}
						</TableCell>
					))}
				</TableRow>
			))}
		</TableBody>
	);
}
