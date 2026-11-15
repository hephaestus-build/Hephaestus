import type { AuthEventView } from "@/api/types.gen";
import { ELEVATION_DESCRIPTION, ElevationBadge } from "@/components/admin/audit/ElevationBadge";
import { prettyJson } from "@/components/admin/audit/pretty-json";
import { refLabel, workspaceLabel } from "@/components/admin/audit/ref-label";
import { formatTimestamp } from "@/components/admin/audit/time-format";
import { DetailRow } from "@/components/common/DetailRow";
import { Badge } from "@/components/ui/badge";
import {
	CodeBlock,
	CodeBlockActions,
	CodeBlockContent,
	CodeBlockCopyButton,
} from "@/components/ui/code-block";
import {
	Sheet,
	SheetContent,
	SheetDescription,
	SheetHeader,
	SheetTitle,
} from "@/components/ui/sheet";
import { hasText } from "@/lib/text";

import { eventLabel, resultLabel } from "./audit-format";

interface AuditEventDetailSheetProps {
	event: AuthEventView | null;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	resolveWorkspaceName?: (id: number) => string | undefined;
}

export function AuditEventDetailSheet({
	event,
	open,
	onOpenChange,
	resolveWorkspaceName,
}: AuditEventDetailSheetProps) {
	const ts = event ? formatTimestamp(event.occurredAt) : null;
	const account = event ? refLabel(event.account, event.accountId) : null;
	const actor = event ? refLabel(event.actor, event.actingAccountId) : null;
	const pretty = event ? prettyJson(event.details) : null;
	const workspaceName =
		event?.workspaceId == null ? undefined : resolveWorkspaceName?.(event.workspaceId);

	return (
		<Sheet open={open} onOpenChange={onOpenChange}>
			<SheetContent side="right" className="w-full overflow-y-auto sm:max-w-lg">
				<SheetHeader>
					<SheetTitle>{event ? eventLabel(event.eventType) : "Audit event"}</SheetTitle>
					<SheetDescription>
						{event ? `Event #${event.id} · ${event.eventType}` : ""}
					</SheetDescription>
				</SheetHeader>

				{event && (
					<dl className="divide-y px-4 pb-4">
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
						<DetailRow label="Result">
							<Badge variant={event.result === "FAILURE" ? "destructive" : "outline"}>
								{resultLabel(event.result)}
							</Badge>
						</DetailRow>
						{hasText(event.failureReason) && (
							<DetailRow label="Failure reason">
								<span className="text-destructive">{event.failureReason}</span>
							</DetailRow>
						)}
						<DetailRow label="Account">
							{hasText(account) ? (
								<span>
									{account}
									{hasText(event.account?.email) && account !== event.account.email && (
										<span className="ml-1 text-xs text-muted-foreground">
											{event.account.email}
										</span>
									)}
								</span>
							) : (
								"—"
							)}
						</DetailRow>
						{event.elevatedViaInstanceAdmin && (
							<DetailRow label="Access">
								<ElevationBadge elevated />
								<span className="ml-2 text-xs text-muted-foreground">{ELEVATION_DESCRIPTION}</span>
							</DetailRow>
						)}
						<DetailRow label="Acting account">{actor ?? "—"}</DetailRow>
						{event.viewedAccountId != null && (
							<DetailRow label="Viewed account">#{event.viewedAccountId}</DetailRow>
						)}
						<DetailRow label="Workspace">
							{event.workspaceId == null ? "—" : workspaceLabel(event.workspaceId, workspaceName)}
						</DetailRow>
						<DetailRow label="IP address">
							<span className="font-mono text-xs">{event.ipAddress ?? "—"}</span>
						</DetailRow>
						<DetailRow label="User agent">
							<span className="text-xs">{event.userAgent ?? "—"}</span>
						</DetailRow>
						<DetailRow label="Raw data">
							{hasText(pretty) ? (
								<CodeBlock size="sm">
									<CodeBlockActions>
										<CodeBlockCopyButton value={pretty} aria-label="Copy raw data" />
									</CodeBlockActions>
									<CodeBlockContent className="max-h-48">{pretty}</CodeBlockContent>
								</CodeBlock>
							) : (
								"—"
							)}
						</DetailRow>
					</dl>
				)}
			</SheetContent>
		</Sheet>
	);
}
