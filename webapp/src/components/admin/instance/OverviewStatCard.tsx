import { Link, type LinkProps } from "@tanstack/react-router";
import type { LucideIcon } from "lucide-react";

import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

interface OverviewStatCardProps {
	label: string;
	value: string | number;
	hint?: string;
	icon: LucideIcon;
	to: LinkProps["to"];
	isLoading?: boolean;
	/** A tile that could not load shows a dash — a zero would read as a fact. */
	isError?: boolean;
}

export function OverviewStatCard({
	label,
	value,
	hint,
	icon: Icon,
	to,
	isLoading = false,
	isError = false,
}: OverviewStatCardProps) {
	return (
		<Link
			to={to}
			className="block rounded-xl outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
		>
			<Card variant="interactive" className="h-full">
				<CardContent className="flex items-start justify-between gap-3">
					<div className="min-w-0 space-y-1">
						<p className="text-sm text-muted-foreground">{label}</p>
						{isLoading ? (
							<Skeleton className="h-8 w-16" />
						) : (
							<p className="text-2xl font-semibold tabular-nums">
								{isError ? (
									<>
										<span aria-hidden>—</span>
										<span className="sr-only">unavailable</span>
									</>
								) : (
									value
								)}
							</p>
						)}
						{!isLoading && (
							<p className="text-xs text-muted-foreground">{isError ? "Couldn't load" : hint}</p>
						)}
					</div>
					<div className="rounded-full bg-muted p-2">
						<Icon className="size-4 text-muted-foreground" aria-hidden />
					</div>
				</CardContent>
			</Card>
		</Link>
	);
}
