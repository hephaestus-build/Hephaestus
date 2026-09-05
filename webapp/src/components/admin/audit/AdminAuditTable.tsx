import { ScrollText } from "lucide-react";
import { useState } from "react";

import type { AuthEventView } from "@/api/types.gen";
import { ElevationBadge } from "@/components/admin/audit-shared/ElevationBadge";
import { FilterLink } from "@/components/admin/audit-shared/FilterLink";
import { refLabel } from "@/components/admin/audit-shared/ref-label";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

import {
	eventLabel,
	eventSeverity,
	resultLabel,
	severityDotClass,
	severityScreenReaderPrefix,
} from "./audit-format";
import { AuditEventDetailSheet } from "./AuditEventDetailSheet";

export interface AdminAuditTableProps {
	events: AuthEventView[];
	isLoading: boolean;
	isError: boolean;
	hasFilter: boolean;
	onResetFilters?: () => void;
	hasNextPage: boolean;
	isFetchingNextPage: boolean;
	onLoadMore: () => void;
	onRetry?: () => void;
	onFilterAccount?: (id: number) => void;
	onFilterActor?: (id: number) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function AdminAuditTable({
	events,
	isLoading,
	isError,
	hasFilter,
	onResetFilters,
	hasNextPage,
	isFetchingNextPage,
	onLoadMore,
	onRetry,
	onFilterAccount,
	onFilterActor,
	resolveWorkspaceName,
}: AdminAuditTableProps) {
	const [detail, setDetail] = useState<AuthEventView | null>(null);
	const [detailOpen, setDetailOpen] = useState(false);

	if (isError) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScrollText />
					</EmptyMedia>
					<EmptyTitle>Couldn&rsquo;t load the audit log</EmptyTitle>
				</EmptyHeader>
				{onRetry && (
					<EmptyContent>
						<Button variant="outline" size="sm" onClick={onRetry}>
							Try again
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	}

	if (events.length === 0 && !isLoading) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScrollText />
					</EmptyMedia>
					<EmptyTitle>{hasFilter ? "No events match your filters" : "No events yet"}</EmptyTitle>
					{!hasFilter && (
						<EmptyDescription>
							Sign-ins, impersonation, role changes, and account deletions will appear here.
						</EmptyDescription>
					)}
				</EmptyHeader>
				{hasFilter && onResetFilters && (
					<EmptyContent>
						<Button variant="outline" onClick={onResetFilters}>
							Reset filters
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	}

	return (
		<div className="space-y-4">
			<Table containerClassName="rounded-md border">
				<TableCaption className="sr-only">Sign-in and account events, newest first</TableCaption>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">Time</TableHead>
						<TableHead scope="col">Event</TableHead>
						<TableHead scope="col">Result</TableHead>
						<TableHead scope="col">Account</TableHead>
						<TableHead scope="col" className="w-0 text-right">
							<span className="sr-only">Details</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{isLoading ? (
					<TableRowsSkeleton columns={["w-24", "w-28", "w-16", "w-24", null]} rows={8} />
				) : (
					<TableBody>
						{events.map((e) => {
							const severity = eventSeverity(e.eventType, e.result);
							const screenReaderPrefix = severityScreenReaderPrefix(severity);
							const { accountId, actingAccountId } = e;
							const account = refLabel(e.account, accountId);
							const actor = refLabel(e.actor, actingAccountId);
							return (
								<TableRow key={e.id}>
									<TableCell className="whitespace-nowrap text-sm text-muted-foreground">
										<RelativeTime value={e.occurredAt} />
									</TableCell>
									<TableCell>
										<span className="flex items-center gap-2" title={e.eventType}>
											<span
												className={`size-1.5 shrink-0 rounded-full ${severityDotClass(severity)}`}
												aria-hidden
											/>
											{screenReaderPrefix && <span className="sr-only">{screenReaderPrefix}</span>}
											<span className="text-sm">{eventLabel(e.eventType)}</span>
											<ElevationBadge elevated={e.elevatedViaInstanceAdmin} />
										</span>
									</TableCell>
									<TableCell>
										<Badge variant={e.result === "FAILURE" ? "destructive" : "outline"}>
											{resultLabel(e.result)}
										</Badge>
									</TableCell>
									<TableCell className="max-w-[12rem]">
										<span className="block truncate">
											{account ? (
												onFilterAccount && accountId != null ? (
													<FilterLink
														label={account}
														title={e.account?.email ?? `Filter by ${account}`}
														onSelect={() => onFilterAccount(accountId)}
													/>
												) : (
													<span title={e.account?.email ?? undefined}>{account}</span>
												)
											) : (
												"—"
											)}
										</span>
										{actor && (
											<span className="block truncate text-xs text-muted-foreground">
												impersonated by{" "}
												{onFilterActor && actingAccountId != null ? (
													<FilterLink
														label={actor}
														title={e.actor?.email ?? `Filter by ${actor}`}
														onSelect={() => onFilterActor(actingAccountId)}
													/>
												) : (
													actor
												)}
											</span>
										)}
									</TableCell>
									<TableCell className="text-right">
										<Button
											type="button"
											variant="ghost"
											size="sm"
											aria-label={`View details: ${eventLabel(e.eventType)}${account ? ` — ${account}` : ""}`}
											onClick={() => {
												setDetail(e);
												setDetailOpen(true);
											}}
										>
											Details
										</Button>
									</TableCell>
								</TableRow>
							);
						})}
					</TableBody>
				)}
			</Table>

			{hasNextPage && (
				<div className="flex justify-center">
					<Button variant="outline" onClick={onLoadMore} disabled={isFetchingNextPage}>
						{isFetchingNextPage ? <Spinner className="mr-2 size-3.5" /> : null}
						Load more
					</Button>
				</div>
			)}

			<AuditEventDetailSheet
				event={detail}
				open={detailOpen}
				onOpenChange={setDetailOpen}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
	);
}
