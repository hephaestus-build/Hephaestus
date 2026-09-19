import { Link } from "@tanstack/react-router";
import { ArrowRight, ScrollText } from "lucide-react";
import type { ReactNode } from "react";

import type { AuthEventView } from "@/api/types.gen";
import {
	eventLabel,
	eventSeverity,
	severityDotClass,
	severityScreenReaderPrefix,
} from "@/components/admin/audit/audit-format";
import { refLabel } from "@/components/admin/audit/ref-label";
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
import { hasText } from "@/lib/text";

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
	const failed = error != null;
	let body: ReactNode;
	if (failed) {
		body = (
			<QueryErrorAlert error={error} title="Couldn't load recent activity" onRetry={onRetry} />
		);
	} else if (isLoading) {
		body = (
			<div className="space-y-3">
				{["a", "b", "c", "d"].map((row) => (
					<Skeleton key={row} className="h-5 w-full" />
				))}
			</div>
		);
	} else if (events.length === 0) {
		body = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScrollText aria-hidden />
					</EmptyMedia>
					<EmptyTitle>No activity yet</EmptyTitle>
					<EmptyDescription>
						Sign-ins, role changes, and impersonations will show up here as they happen.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else {
		body = (
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
							{hasText(screenReaderPrefix) && <span className="sr-only">{screenReaderPrefix}</span>}
							<span className="min-w-0 truncate">{eventLabel(event.eventType)}</span>
							{hasText(actor) ? (
								<span className="min-w-0 truncate text-muted-foreground">{actor}</span>
							) : null}
							<RelativeTime
								value={event.occurredAt}
								className="ml-auto shrink-0 text-xs whitespace-nowrap"
							/>
						</li>
					);
				})}
			</ul>
		);
	}

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
			<CardContent>{body}</CardContent>
		</Card>
	);
}
