import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableRow } from "@/components/ui/table";

const SKELETON_ROWS = 6;

/** A person's row before the people arrive, for the table and for the page that has not read yet. */
export function PeopleSkeletonRows({ colSpan }: { colSpan?: number }) {
	return Array.from({ length: SKELETON_ROWS }, (_, index) => (
		<TableRow key={index} variant="static" aria-hidden>
			<TableCell colSpan={colSpan}>
				<div className="flex items-center gap-3">
					<Skeleton className="size-8 rounded-full" />
					<div className="space-y-1.5">
						<Skeleton className="h-4 w-40" />
						<Skeleton className="h-3 w-16" />
					</div>
					<Skeleton className="ml-auto h-4 w-1/2" />
				</div>
			</TableCell>
		</TableRow>
	));
}
