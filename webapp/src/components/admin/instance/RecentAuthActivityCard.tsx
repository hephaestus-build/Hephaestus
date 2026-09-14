import { Link } from "@tanstack/react-router";
import { ArrowRight, ScrollText } from "lucide-react";

import type { AuthEventView } from "@/api/types.gen";
import { refLabel } from "@/components/admin/audit-shared/ref-label";
import {
	eventLabel,
	eventSeverity,
	severityDotClass,
	severityScreenReaderPrefix,
} from "@/components/admin/audit/audit-format";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { buttonVariants } from "@/components/ui/button";
import {
	Card,
	CardAction,
	CardContent,
	CardDescription,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

interface RecentAuthActivityCardProps {
	events: AuthEventView[];
	isLoading?: boolean;
	/** Set when the log could not be read, so the empty state never doubles as "nothing happened". */
	error?: unknown;
	onRetry?: () => void;
}

export function RecentAuthActivityCard({
	events,
	isLoading = false,
	error,
	onRetry,
}: RecentAuthActivityCardProps) {
	return (
		<Card>
			<CardHeader>
				<CardTitle>Recent activity</CardTitle>
				<CardDescription>Latest authentication and admin events</CardDescription>
				<CardAction>
					<Link
						to="/admin/audit"
						search={{ tab: "signins" }}
						className={buttonVariants({ variant: "ghost", size: "sm" })}
					>
						View audit log
						<ArrowRight aria-hidden />
					</Link>
				</CardAction>
			</CardHeader>
			<CardContent>
				{error ? (
					<QueryErrorAlert error={error} title="Couldn't load recent activity" onRetry={onRetry} />
				) : isLoading ? (
					<div className="space-y-3">
						{["a", "b", "c", "d"].map((row) => (
							<Skeleton key={row} className="h-5 w-full" />
						))}
					</div>
				) : events.length === 0 ? (
					<Empty className="py-8">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<ScrollText aria-hidden />
							</EmptyMedia>
							<EmptyTitle>No activity yet</EmptyTitle>
							<EmptyDescription>
								Sign-ins, role changes, and user views will show up here as they happen.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<ul className="space-y-2.5">
						{events.map((event) => {
							const severity = eventSeverity(event.eventType, event.result);
							const screenReaderPrefix = severityScreenReaderPrefix(severity);
							const actor =
								refLabel(event.actor, event.actingAccountId) ??
								refLabel(event.account, event.accountId);
							return (
								<li key={event.id} className="flex min-w-0 items-center gap-2 text-sm">
									<span
										className={`size-1.5 shrink-0 rounded-full ${severityDotClass(severity)}`}
										aria-hidden
									/>
									{screenReaderPrefix && <span className="sr-only">{screenReaderPrefix}</span>}
									<span className="min-w-0 truncate">{eventLabel(event.eventType)}</span>
									{actor ? (
										<span className="min-w-0 truncate text-muted-foreground">{actor}</span>
									) : null}
									<RelativeTime
										value={event.occurredAt}
										className="ml-auto shrink-0 whitespace-nowrap text-xs"
									/>
								</li>
							);
						})}
					</ul>
				)}
			</CardContent>
		</Card>
	);
}
