import { Skeleton } from "@/components/ui/skeleton";

/**
 * The shapes the practice surfaces resolve into, drawn before the data arrives.
 *
 * Every row count is a required prop: a default is a count that can silently disagree with the
 * caller's page size, which is the jump a skeleton exists to prevent.
 */
export interface PracticeListSkeletonProps {
	rows: number;
}

export function PracticeListSkeleton({ rows }: PracticeListSkeletonProps) {
	return (
		<div className="space-y-2" aria-hidden>
			{Array.from({ length: rows }, (_, index) => (
				<div key={index} className="flex items-center gap-3 rounded-lg border p-3">
					<Skeleton className="size-8 shrink-0 rounded-md" />
					<div className="min-w-0 flex-1 space-y-2">
						<Skeleton className="h-4 w-full max-w-64" />
						<Skeleton className="h-3 w-full max-w-40" />
					</div>
					<Skeleton className="h-5 w-16 shrink-0 rounded-md" />
				</div>
			))}
		</div>
	);
}

export interface PracticeTreeSkeletonProps {
	groups: number;
	practicesPerGroup: number;
}

export function PracticeTreeSkeleton({ groups, practicesPerGroup }: PracticeTreeSkeletonProps) {
	return (
		<div className="space-y-6" aria-hidden>
			{Array.from({ length: groups }, (_, index) => (
				<div key={index} className="space-y-3">
					<div className="flex items-center gap-2">
						<Skeleton className="size-6 rounded-md" />
						<Skeleton className="h-5 w-full max-w-56" />
					</div>
					<PracticeListSkeleton rows={practicesPerGroup} />
				</div>
			))}
		</div>
	);
}

/**
 * Mirrors {@link PracticeDefinitionPreview}, so the panel does not reflow when the definition lands.
 */
export function PracticeDefinitionSkeleton() {
	return (
		<div className="space-y-6" aria-hidden>
			<div className="space-y-2">
				<Skeleton className="h-5 w-full max-w-2xl" />
				<Skeleton className="h-5 w-full max-w-xl" />
			</div>
			<div className="space-y-2">
				<Skeleton className="h-4 w-32" />
				<Skeleton className="h-4 w-full max-w-lg" />
			</div>
			<div className="space-y-px pt-2">
				{Array.from({ length: 3 }, (_, index) => (
					<Skeleton key={index} className="h-12 w-full" />
				))}
			</div>
		</div>
	);
}

export interface PracticeReleaseSkeletonProps {
	/** Changed fields to draw. The count is unknown until the update loads, so the caller guesses. */
	rows: number;
}

/** Mirrors {@link PracticeReleaseReview}: its heading, the field comparison, and the two actions. */
export function PracticeReleaseSkeleton({ rows }: PracticeReleaseSkeletonProps) {
	return (
		<div aria-busy="true">
			<span className="sr-only">Loading the update…</span>
			<div className="space-y-5" aria-hidden>
				<div className="space-y-2">
					<Skeleton className="h-6 w-full max-w-56" />
					<Skeleton className="h-4 w-full max-w-lg" />
				</div>
				<div className="space-y-px">
					{Array.from({ length: rows + 1 }, (_, index) => (
						<Skeleton key={index} className="h-12 w-full" />
					))}
				</div>
				<div className="flex flex-wrap gap-2">
					<Skeleton className="h-9 w-44" />
					<Skeleton className="h-9 w-40" />
				</div>
			</div>
		</div>
	);
}

export function ReviewSettingsSkeleton() {
	return (
		<div aria-busy="true">
			<span className="sr-only">Loading review settings…</span>
			<div className="space-y-8" aria-hidden>
				{["status", "timing", "coverage"].map((section) => (
					<div key={section} className="space-y-4">
						<div className="space-y-2">
							<Skeleton className="h-6 w-48" />
							<Skeleton className="h-4 w-full max-w-lg" />
						</div>
						{["first", "second"].map((row) => (
							<Skeleton key={`${section}-${row}`} className="h-10 w-full" />
						))}
					</div>
				))}
			</div>
		</div>
	);
}
