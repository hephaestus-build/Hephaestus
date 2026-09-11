import type { ConfigAuditEntryView } from "@/api/types.gen";
import {
	ELEVATION_DESCRIPTION,
	ElevationBadge,
} from "@/components/admin/audit-shared/ElevationBadge";
import { prettyJson } from "@/components/admin/audit-shared/pretty-json";
import { formatTimestamp } from "@/components/admin/audit-shared/time-format";
import { DetailRow } from "@/components/common/DetailRow";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Sheet,
	SheetContent,
	SheetDescription,
	SheetHeader,
	SheetTitle,
} from "@/components/ui/sheet";
import { hasText } from "@/lib/text";

import {
	ACTION_BADGE,
	actionLabel,
	actorDisplay,
	entityTypeLabel,
	fieldChanges,
	subjectLabel,
} from "./config-audit-format";

export interface ConfigAuditDetailSheetProps {
	entry: ConfigAuditEntryView | null;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function ConfigAuditDetailSheet({
	entry,
	open,
	onOpenChange,
	resolveWorkspaceName,
}: ConfigAuditDetailSheetProps) {
	const ts = entry ? formatTimestamp(entry.occurredAt) : null;
	const changes = entry ? fieldChanges(entry) : [];
	const actor = entry ? actorDisplay(entry) : null;
	const subject = entry ? subjectLabel(entry) : null;
	const workspaceName =
		entry?.workspaceId != null ? resolveWorkspaceName?.(entry.workspaceId) : undefined;
	const oldRaw = prettyJson(entry?.oldValue);
	const newRaw = prettyJson(entry?.newValue);
	const valuesHeading =
		entry?.action === "CREATED"
			? "Initial values"
			: entry?.action === "DELETED"
				? "Final values"
				: "Changes";

	return (
		<Sheet open={open} onOpenChange={onOpenChange}>
			<SheetContent side="right" className="w-full overflow-y-auto sm:max-w-lg">
				<SheetHeader>
					<SheetTitle>{entry ? subject?.label : "Settings change"}</SheetTitle>
					<SheetDescription>
						{entry ? `${actionLabel(entry.action)} — ${entityTypeLabel(entry.entityType)}` : ""}
					</SheetDescription>
				</SheetHeader>

				{entry && actor && (
					<div className="space-y-4 px-4 pb-4">
						<dl className="divide-y">
							<DetailRow label="Time">
								{ts ? (
									<>
										<span>{ts.local}</span>
										<span className="ml-2 text-xs text-muted-foreground">({ts.isoUtc})</span>
									</>
								) : (
									"—"
								)}
							</DetailRow>
							<DetailRow label="Action">
								<Badge variant={ACTION_BADGE[entry.action ?? "UPDATED"]}>
									{actionLabel(entry.action)}
								</Badge>
							</DetailRow>
							<DetailRow label="Setting">
								<span>{entityTypeLabel(entry.entityType)}</span>
								{entry.entityId && (
									<span className="ml-2 font-mono text-xs text-muted-foreground">
										{entry.entityId}
									</span>
								)}
							</DetailRow>
							<DetailRow label="Actor">
								{actor.kind === "SYSTEM" ? (
									<span className="text-muted-foreground">System</span>
								) : (
									<span>
										{actor.primary}
										{actor.primaryEmail && actor.primaryEmail !== actor.primary && (
											<span className="ml-1 text-xs text-muted-foreground">
												{actor.primaryEmail}
											</span>
										)}
									</span>
								)}
							</DetailRow>
							{entry.elevatedViaInstanceAdmin && (
								<DetailRow label="Access">
									<ElevationBadge elevated />
									<span className="ml-2 text-xs text-muted-foreground">
										{ELEVATION_DESCRIPTION}
									</span>
								</DetailRow>
							)}
							{actor.actingAs && <DetailRow label="Impersonating">{actor.actingAs}</DetailRow>}
							<DetailRow label="Workspace">
								{entry.workspaceId == null
									? "Instance-wide"
									: workspaceName
										? `${workspaceName} (#${entry.workspaceId})`
										: `#${entry.workspaceId}`}
							</DetailRow>
						</dl>

						<div>
							<h3 className="mb-2 text-sm font-medium">{valuesHeading}</h3>
							{changes.length === 0 ? (
								<p className="text-sm text-muted-foreground">No field changes recorded</p>
							) : (
								<dl className="divide-y rounded-md border">
									{changes.map((change) => (
										<div key={change.path} className="grid grid-cols-[10rem_1fr] gap-2 p-2 text-sm">
											<dt
												className="truncate font-mono text-xs text-muted-foreground"
												title={change.path}
											>
												{change.path}
											</dt>
											<dd className="min-w-0 break-words">
												{entry.action === "CREATED" ? (
													<span>{change.after ?? "—"}</span>
												) : entry.action === "DELETED" ? (
													<span>{change.before ?? "—"}</span>
												) : (
													<span>
														<span className="sr-only">changed from </span>
														<span className="text-muted-foreground line-through decoration-muted-foreground/50">
															{change.before ?? "—"}
														</span>
														<span aria-hidden className="mx-1.5 text-muted-foreground">
															→
														</span>
														<span className="sr-only"> to </span>
														<span className="font-medium">{change.after ?? "—"}</span>
													</span>
												)}
											</dd>
										</div>
									))}
								</dl>
							)}
						</div>

						{(hasText(oldRaw) || hasText(newRaw)) && (
							<Collapsible key={entry.id}>
								<CollapsibleTrigger
									render={
										<Button
											type="button"
											variant="ghost"
											size="sm"
											className="px-0 text-xs text-muted-foreground"
										/>
									}
								>
									Show raw snapshots
								</CollapsibleTrigger>
								<CollapsibleContent className="mt-2 space-y-2">
									{oldRaw && (
										<div>
											<p className="mb-1 text-xs text-muted-foreground">Before</p>
											<pre className="max-h-48 overflow-auto rounded bg-muted p-2 text-xs">
												{oldRaw}
											</pre>
										</div>
									)}
									{newRaw && (
										<div>
											<p className="mb-1 text-xs text-muted-foreground">After</p>
											<pre className="max-h-48 overflow-auto rounded bg-muted p-2 text-xs">
												{newRaw}
											</pre>
										</div>
									)}
								</CollapsibleContent>
							</Collapsible>
						)}
					</div>
				)}
			</SheetContent>
		</Sheet>
	);
}
