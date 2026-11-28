import type { ReactNode } from "react";

import type { SupportShare } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { SHARE_STATE_LABELS } from "@/components/support-share/support-share-words";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDayTime } from "@/lib/dates";

export type SupportSharesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; shares: SupportShare[] };

export interface SupportSharesSectionProps {
	state: SupportSharesState;
	/** The share being revoked now, if any. */
	revoking?: string;
	onRevoke: (shareId: string) => void;
}

/** How often the admin read a share, and when last: the person's own record of every read. */
function readsText(share: SupportShare, today: Date): string {
	if (share.readCount === 0 || share.lastReadAt === undefined) {
		return "Not read yet";
	}
	const times = share.readCount === 1 ? "once" : `${share.readCount} times`;
	return `Read ${times}, last ${formatDayTime(share.lastReadAt, today)}`;
}

/** Until when an active share runs, or when an ended one ended. */
function endText(share: SupportShare, today: Date): string {
	if (share.state === "ACTIVE") {
		return `until ${formatDayTime(share.expiresAt, today)}`;
	}
	if (share.state === "REVOKED" && share.revokedAt !== undefined) {
		return `revoked ${formatDayTime(share.revokedAt, today)}`;
	}
	return `ended ${formatDayTime(share.expiresAt, today)}`;
}

/**
 * The questions the person shared with an instance admin: to whom, until when, and every read. An
 * active share can be revoked here at once; an ended one stays as the person's record.
 */
export function SupportSharesSection({ state, revoking, onRevoke }: SupportSharesSectionProps) {
	const today = new Date(useNow());
	let body: ReactNode;
	if (state.status === "loading") {
		body = (
			<div className="space-y-3" role="list" aria-busy="true" aria-label="Loading support shares…">
				{Array.from({ length: 2 }, (_, index) => (
					<div
						key={index}
						role="listitem"
						className="flex items-start justify-between gap-4 rounded-lg border p-4"
					>
						<div className="min-w-0 flex-1 space-y-1.5">
							<Skeleton className="h-4 w-40" />
							<Skeleton className="h-4 w-72 max-w-full" />
							<Skeleton className="h-4 w-32" />
						</div>
						<Skeleton className="h-8 w-16 shrink-0" />
					</div>
				))}
			</div>
		);
	} else if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="We could not load your support shares"
				onRetry={state.onRetry}
			/>
		);
	} else if (state.shares.length === 0) {
		body = (
			<p className="text-sm text-muted-foreground">
				You have not shared a question. To share one, choose Share with support on the question or
				its answer.
			</p>
		);
	} else {
		body = (
			<ul aria-label="Your support shares" className="space-y-3">
				{state.shares.map((share) => (
					<li
						key={share.id}
						className="flex flex-wrap items-start justify-between gap-4 rounded-lg border p-4"
					>
						<div className="min-w-0 space-y-1">
							<p className="flex flex-wrap items-center gap-2 text-sm font-medium">
								{share.recipient.displayName}
								<Badge variant={share.state === "ACTIVE" ? "default" : "secondary"}>
									{SHARE_STATE_LABELS[share.state]}
								</Badge>
							</p>
							<p className="text-sm text-muted-foreground">
								{share.includesText ? "Diagnostics and messages" : "Diagnostics only"} · shared{" "}
								{formatDayTime(share.createdAt, today)} · {endText(share, today)}
							</p>
							<p className="text-sm text-muted-foreground">{readsText(share, today)}</p>
						</div>
						{share.state === "ACTIVE" && (
							<Button
								variant="outline"
								size="sm"
								pending={revoking === share.id}
								onClick={() => onRevoke(share.id)}
							>
								Revoke
							</Button>
						)}
					</li>
				))}
			</ul>
		);
	}
	return (
		<section className="space-y-4" aria-labelledby="support-shares-heading">
			<div className="space-y-1">
				<h2 id="support-shares-heading" className="text-xl font-semibold">
					Support shares
				</h2>
				<p className="text-sm text-muted-foreground">
					Questions you shared with one instance admin. Each share ends after 7 days, or when you
					revoke it. No admin can read your conversation any other way.
				</p>
			</div>
			{body}
		</section>
	);
}
