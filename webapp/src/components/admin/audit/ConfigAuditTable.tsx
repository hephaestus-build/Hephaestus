import { Bot, History, UserCog } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { ConfigAuditEntryView } from "@/api/types.gen";
import { ElevationBadge } from "@/components/admin/audit/ElevationBadge";
import { FilterLink } from "@/components/admin/audit/FilterLink";
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

import type { AuditListState } from "./audit-list";
import { AuditListEnd } from "./AuditListEnd";
import {
	ACTION_BADGE,
	actionLabel,
	actorDisplay,
	changeSummary,
	subjectLabel,
} from "./config-audit-format";
import { ConfigAuditDetailSheet } from "./ConfigAuditDetailSheet";

export interface ConfigAuditTableProps {
	state: AuditListState<ConfigAuditEntryView>;
	hasFilter: boolean;
	onResetFilters?: () => void;
	onFilterActor?: (id: number) => void;
	showWorkspace?: boolean;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function ConfigAuditTable({
	state,
	hasFilter,
	onResetFilters,
	showWorkspace = false,
	...rowActions
}: ConfigAuditTableProps) {
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
			<ConfigEntriesTable showWorkspace={showWorkspace} busy>
				<TableRowsSkeleton columns={skeletonColumns(showWorkspace)} rows={8} />
			</ConfigEntriesTable>
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
							<History />
						</EmptyMedia>
						<EmptyTitle>
							{hasFilter ? "No changes match your filters" : "No settings changes yet"}
						</EmptyTitle>
						{!hasFilter && (
							<EmptyDescription>
								Changes to settings, member roles, feature flags, and AI configuration will appear
								here.
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
				<ConfigEntryRows list={state} showWorkspace={showWorkspace} {...rowActions} />
			)}
		</div>
	);
}

/** Time, action, setting, the workspace where shown, actor, and the details press. */
function skeletonColumns(showWorkspace: boolean) {
	return showWorkspace
		? ["w-24", "w-16", "w-32", "w-24", "w-24", null]
		: ["w-24", "w-16", "w-32", "w-24", null];
}

function ConfigEntriesTable({
	showWorkspace,
	busy = false,
	children,
}: {
	showWorkspace: boolean;
	busy?: boolean;
	children: ReactNode;
}) {
	return (
		<Table bordered aria-busy={busy || undefined}>
			<TableCaption className="sr-only">Settings changes, newest first</TableCaption>
			<TableHeader>
				<TableRow>
					<TableHead scope="col">Time</TableHead>
					<TableHead scope="col">Action</TableHead>
					<TableHead scope="col">Setting</TableHead>
					{showWorkspace && <TableHead scope="col">Workspace</TableHead>}
					<TableHead scope="col">Actor</TableHead>
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
 * The rows of a read that stands, and the change opened from them. Mounted only while that read
 * stands, so a refused read closes the change. Another workspace or filter is another read: the host
 * keys the table by it.
 */
function ConfigEntryRows({
	list,
	showWorkspace,
	onFilterActor,
	resolveWorkspaceName,
}: Pick<ConfigAuditTableProps, "onFilterActor" | "resolveWorkspaceName"> & {
	list: Extract<AuditListState<ConfigAuditEntryView>, { status: "ready" }>;
	showWorkspace: boolean;
}) {
	const [detail, setDetail] = useState<ConfigAuditEntryView | null>(null);
	const [detailOpen, setDetailOpen] = useState(false);
	return (
		<>
			<ConfigEntriesTable showWorkspace={showWorkspace} busy={list.isLoadingMore}>
				<TableBody>
					{list.rows.map((entry) => {
						const actor = actorDisplay(entry);
						const subject = subjectLabel(entry);
						const summary = changeSummary(entry);
						const workspaceName =
							entry.workspaceId == null ? undefined : resolveWorkspaceName?.(entry.workspaceId);
						return (
							<TableRow key={entry.id}>
								<TableCell className="text-sm whitespace-nowrap text-muted-foreground">
									<RelativeTime value={entry.occurredAt} />
								</TableCell>
								<TableCell>
									<Badge variant={ACTION_BADGE[entry.action ?? "UPDATED"]}>
										{actionLabel(entry.action)}
									</Badge>
								</TableCell>
								<TableCell className="max-w-[14rem] truncate" title={subject.hint ?? subject.label}>
									{subject.label}
								</TableCell>
								{showWorkspace && (
									<TableCell className="max-w-[10rem] truncate text-sm text-muted-foreground">
										{entry.workspaceId == null
											? "Instance-wide"
											: (workspaceName ?? `#${entry.workspaceId}`)}
									</TableCell>
								)}
								<TableCell className="max-w-[14rem]">
									<span className="flex min-w-0 items-center gap-1.5">
										<ActorCell actor={actor} onFilterActor={onFilterActor} />
										<ElevationBadge elevated={entry.elevatedViaInstanceAdmin} />
									</span>
									<span className="block truncate text-xs text-muted-foreground" title={summary}>
										{summary}
									</span>
								</TableCell>
								<TableCell className="text-right">
									<Button
										type="button"
										variant="ghost"
										size="sm"
										aria-label={`View details for ${subject.label}, ${actionLabel(entry.action).toLowerCase()}`}
										onClick={() => {
											setDetail(entry);
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
					<TableRowsSkeleton
						columns={skeletonColumns(showWorkspace)}
						rows={LOADING_MORE_ROWS}
						continues
					/>
				)}
			</ConfigEntriesTable>

			<AuditListEnd list={list} noun="changes" />

			<ConfigAuditDetailSheet
				entry={detail}
				open={detailOpen}
				onOpenChange={setDetailOpen}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</>
	);
}

function ActorCell({
	actor,
	onFilterActor,
}: {
	actor: ReturnType<typeof actorDisplay>;
	onFilterActor?: (id: number) => void;
}) {
	if (actor.kind === "SYSTEM") {
		return (
			<span className="flex items-center gap-1.5 text-muted-foreground">
				<Bot className="size-3.5 shrink-0" aria-hidden />
				System
			</span>
		);
	}
	const { filterId } = actor;
	return (
		<span className="flex items-center gap-1.5">
			{actor.kind === "IMPERSONATED" && <UserCog className="size-3.5 shrink-0" aria-hidden />}
			{onFilterActor && filterId != null ? (
				<FilterLink
					label={actor.primary}
					title={actor.primaryEmail ?? `Filter by ${actor.primary}`}
					onSelect={() => onFilterActor(filterId)}
				/>
			) : (
				<span className="truncate" title={actor.primaryEmail ?? undefined}>
					{actor.primary}
				</span>
			)}
			{hasText(actor.actingAs) && (
				<span className="truncate text-xs text-muted-foreground">acting as {actor.actingAs}</span>
			)}
		</span>
	);
}
