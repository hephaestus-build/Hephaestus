import { Bot, History, UserCog } from "lucide-react";
import { useState } from "react";

import type { ConfigAuditEntryView } from "@/api/types.gen";
import { ElevationBadge } from "@/components/admin/audit-shared/ElevationBadge";
import { FilterLink } from "@/components/admin/audit-shared/FilterLink";
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
	ACTION_BADGE,
	actionLabel,
	actorDisplay,
	changeSummary,
	subjectLabel,
} from "./config-audit-format";
import { ConfigAuditDetailSheet } from "./ConfigAuditDetailSheet";

export interface ConfigAuditTableProps {
	entries: ConfigAuditEntryView[];
	isLoading: boolean;
	isError: boolean;
	hasFilter: boolean;
	onResetFilters?: () => void;
	hasNextPage: boolean;
	isFetchingNextPage: boolean;
	onLoadMore: () => void;
	onRetry?: () => void;
	onFilterActor?: (id: number) => void;
	showWorkspace?: boolean;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function ConfigAuditTable({
	entries,
	isLoading,
	isError,
	hasFilter,
	onResetFilters,
	hasNextPage,
	isFetchingNextPage,
	onLoadMore,
	onRetry,
	onFilterActor,
	showWorkspace = false,
	resolveWorkspaceName,
}: ConfigAuditTableProps) {
	const [detail, setDetail] = useState<ConfigAuditEntryView | null>(null);
	const [detailOpen, setDetailOpen] = useState(false);

	if (isError) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<History />
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

	if (entries.length === 0 && !isLoading) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<History />
					</EmptyMedia>
					<EmptyTitle>
						{hasFilter ? "No changes match your filters" : "No settings changes yet"}
					</EmptyTitle>
					{!hasFilter && (
						<EmptyDescription>
							Changes to workspace settings, member roles, feature flags, and AI configuration will
							appear here.
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
				{isLoading ? (
					<TableRowsSkeleton
						columns={
							showWorkspace
								? ["w-24", "w-16", "w-32", "w-24", "w-24", null]
								: ["w-24", "w-16", "w-32", "w-24", null]
						}
						rows={8}
					/>
				) : (
					<TableBody>
						{entries.map((entry) => {
							const actor = actorDisplay(entry);
							const subject = subjectLabel(entry);
							const summary = changeSummary(entry);
							const workspaceName =
								entry.workspaceId != null ? resolveWorkspaceName?.(entry.workspaceId) : undefined;
							return (
								<TableRow key={entry.id}>
									<TableCell className="whitespace-nowrap text-sm text-muted-foreground">
										<RelativeTime value={entry.occurredAt} />
									</TableCell>
									<TableCell>
										<Badge variant={ACTION_BADGE[entry.action ?? "UPDATED"]}>
											{actionLabel(entry.action)}
										</Badge>
									</TableCell>
									<TableCell
										className="max-w-[14rem] truncate"
										title={subject.hint ?? subject.label}
									>
										{subject.label}
									</TableCell>
									{showWorkspace && (
										<TableCell className="max-w-[10rem] truncate text-sm text-muted-foreground">
											{entry.workspaceId != null
												? (workspaceName ?? `#${entry.workspaceId}`)
												: "Instance-wide"}
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
											aria-label={`View details: ${actionLabel(entry.action)} ${subject.label}`}
											onClick={() => {
												setDetail(entry);
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

			<ConfigAuditDetailSheet
				entry={detail}
				open={detailOpen}
				onOpenChange={setDetailOpen}
				resolveWorkspaceName={resolveWorkspaceName}
			/>
		</div>
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
			{actor.actingAs && (
				<span className="truncate text-xs text-muted-foreground">acting as {actor.actingAs}</span>
			)}
		</span>
	);
}
