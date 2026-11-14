import { cn } from "cn";
import type { ReactNode } from "react";
import { useSpinDelay } from "spin-delay";

import { Card, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * The stand-ins show only after a second. The provider options usually arrive sooner, and a
 * stand-in that shows and goes at once reads as a fault. A caller mounts one only while the read is
 * on its way, so it has no minimum time to hold. `ssr: false`, or a stand-in that mounts with the
 * read in flight shows at once.
 *
 * The shapes are aria-hidden, and a screen reader hears what loads instead.
 */
function WhileLoading({ label, children }: { label: string; children: ReactNode }) {
	const shown = useSpinDelay(true, { delay: 1000, minDuration: 0, ssr: false });
	if (!shown) {
		return null;
	}
	return (
		<div aria-busy="true">
			<span className="sr-only">{label}</span>
			<div aria-hidden>{children}</div>
		</div>
	);
}

/**
 * The provider choice while it loads: the two cards of the provider selection page, in the same
 * grid and the same card.
 */
export function ProviderChoiceSkeleton() {
	return (
		<WhileLoading label="Loading the providers…">
			<div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
				{[0, 1].map((index) => (
					<Card key={index} className="h-full">
						<CardHeader>
							<div className="mb-1 flex h-7 items-center gap-3">
								<Skeleton className="size-6" />
								<Skeleton className="h-5 w-20" />
							</div>
							<div>
								{/* The GitHub card's words run to three lines in a half-width card, and the grid gives both cards the tallest. */}
								{["w-full", "w-full", "w-3/5"].map((width, line) => (
									<div key={line} className="flex h-5 items-center">
										<Skeleton className={cn("h-4", width)} />
									</div>
								))}
							</div>
						</CardHeader>
					</Card>
				))}
			</div>
		</WhileLoading>
	);
}

/** The GitHub install action while it loads: the full-width link button that replaces it. */
export function InstallActionSkeleton() {
	return (
		<WhileLoading label="Loading the GitHub App link…">
			<Skeleton className="h-8 w-full" />
		</WhileLoading>
	);
}

/**
 * The GitLab wizard while it loads, in the shape of its first step: the heading and description, the
 * progress indicator, then the instance and token fields above the validate button. Checking which
 * accounts are linked decides what follows, so no step's own words stand in for it.
 */
export function GitLabWizardSkeleton() {
	return (
		<WhileLoading label="Loading the GitLab setup…">
			<div className="mx-auto w-full max-w-2xl">
				<Skeleton className="mb-6 h-5 w-14" />
				<div className="mb-6 space-y-1.5">
					<Skeleton className="h-8 w-64 max-w-full" />
					<Skeleton className="h-5 w-full max-w-md" />
				</div>
				<div className="flex items-center gap-2">
					{[0, 1, 2].map((index) => (
						<div key={index} className="flex items-center gap-2">
							<Skeleton className="size-6 rounded-full" />
							<Skeleton className="hidden h-5 w-16 sm:block" />
							{index < 2 && <Skeleton className="h-px w-6 sm:w-8" />}
						</div>
					))}
				</div>
				<div className="mt-6 flex flex-col gap-4">
					<div className="flex flex-col gap-2">
						<Skeleton className="h-5 w-28" />
						<Skeleton className="h-8 w-full" />
						<Skeleton className="h-5 w-3/4" />
					</div>
					<div className="flex flex-col gap-2">
						<Skeleton className="h-5 w-24" />
						<Skeleton className="h-8 w-full" />
						<Skeleton className="h-10 w-full" />
					</div>
					<Skeleton className="h-8 w-full" />
				</div>
			</div>
		</WhileLoading>
	);
}
