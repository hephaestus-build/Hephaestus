import { ScrollText } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { AuthEventView } from "@/api/types.gen";
import { ElevationBadge } from "@/components/admin/audit/ElevationBadge";
import { FilterLink } from "@/components/admin/audit/FilterLink";
import { refLabel } from "@/components/admin/audit/ref-label";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
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
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { hasText } from "@/lib/text";

import { AdminAuditEventDetailSheet } from "./AdminAuditEventDetailSheet";
import {
	eventLabel,
	eventSeverity,
	resultLabel,
	severityDotClass,
	severityScreenReaderPrefix,
} from "./audit-format";
import type { AuditListState } from "./audit-list";
import { AuditListEnd } from "./AuditListEnd";

export interface AdminAuditTableProps {
	state: AuditListState<AuthEventView>;
	hasFilter: boolean;
	onResetFilters?: () => void;
	onFilterAccount?: (id: number) => void;
	onFilterActor?: (id: number) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function AdminAuditTable({
	state,
	hasFilter,
	onResetFilters,
	...rowActions
}: AdminAuditTableProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the audit log"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<AuthEventsTable busy>
				<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={8} />
			</AuthEventsTable>
		);
	}
	return (
		<div className="space-y-4">
			{state.refreshFailure !== undefined && (
				<QueryErrorAlert title="We could not refresh the audit log" {...state.refreshFailure} />
			)}
			{state.rows.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<ScrollText />
						</EmptyMedia>
						<EmptyTitle>{hasFilter ? "No events match your filters" : "No events yet"}</EmptyTitle>
						{!hasFilter && (
							<EmptyDescription>
								Sign-ins, user views, role changes, and account deletions will appear here.
							</EmptyDescription>
						)}
					</EmptyHeader>
					{hasFilter && onResetFilters && (
						<EmptyContent>
							<Button variant="outline" onClick={onResetFilters}>
								Clear filters
							</Button>
						</EmptyContent>
					)}
				</Empty>
			) : (
				<AuthEventRows list={state} {...rowActions} />
			)}
		</div>
	);
}

/** Time, event, result, account, and the details press, which promises nothing. */
const SKELETON_COLUMNS = ["w-24", "w-28", "w-16", "w-24", null];

function AuthEventsTable({ busy = false, children }: { busy?: boolean; children: ReactNode }) {
	return (
		<Table bordered aria-busy={busy || undefined}>
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
			{children}
		</Table>
	);
}

/**
 * The rows of a read that stands, and the event opened from them. Mounted only while that read
 * stands, so a refused read closes the event. Another filter is another read: the host keys the
 * table by it.
 */
function AuthEventRows({
	list,
	onFilterAccount,
	onFilterActor,
	resolveWorkspaceName,
}: Pick<AdminAuditTableProps, "onFilterAccount" | "onFilterActor" | "resolveWorkspaceName"> & {
	list: Extract<AuditListState<AuthEventView>, { status: "ready" }>;
}) {
	const [detail, setDetail] = useState<AuthEventView | null>(null);
	const [detailOpen, setDetailOpen] = useState(false);
	return (
		<>
			<AuthEventsTable busy={list.isLoadingMore}>
				<TableBody>
					{list.rows.map((e) => {
						const severity = eventSeverity(e.eventType, e.result);
						const screenReaderPrefix = severityScreenReaderPrefix(severity);
						const { accountId, actingAccountId } = e;
						const account = refLabel(e.account, accountId);
						const actor = refLabel(e.actor, actingAccountId);
						let accountCell: ReactNode = "—";
						if (hasText(account)) {
							accountCell =
								onFilterAccount && accountId != null ? (
									<FilterLink
										label={account}
										title={e.account?.email ?? `Filter by ${account}`}
										onSelect={() => onFilterAccount(accountId)}
									/>
								) : (
									<span title={e.account?.email ?? undefined}>{account}</span>
								);
						}
						return (
							<TableRow key={e.id}>
								<TableCell className="text-sm whitespace-nowrap text-muted-foreground">
									<RelativeTime value={e.occurredAt} />
								</TableCell>
								<TableCell>
									<span className="flex items-center gap-2" title={e.eventType}>
										<span
											className={`size-1.5 shrink-0 rounded-full ${severityDotClass(severity)}`}
											aria-hidden
										/>
										{hasText(screenReaderPrefix) && (
											<span className="sr-only">{screenReaderPrefix}</span>
										)}
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
									<span className="block truncate">{accountCell}</span>
									{hasText(actor) && (
										<span className="block truncate text-xs text-muted-foreground">
											by{" "}
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
										aria-label={`View details for ${eventLabel(e.eventType)}${hasText(account) ? `, ${account}` : ""}`}
										onClick={() => {
											setDetail(e);
											setDetailOpen(true);
										}}
									>
										View details
									</Button>
								</TableCell>
							</TableRow>
						);
					})}
				</TableBody>
				{list.isLoadingMore && (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={LOADING_MORE_ROWS} continues />
				)}
			</AuthEventsTable>

			<AuditListEnd list={list} noun="events" />

			<AdminAuditEventDetailSheet
				event={detail}
				open={detailOpen}
				onOpenChange={setDetailOpen}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</>
	);
}
