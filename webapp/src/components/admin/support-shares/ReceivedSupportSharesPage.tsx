import { LifeBuoyIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { SupportShareReceived } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { SupportShareContent } from "@/components/support-share/SupportShareContent";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDayTime } from "@/lib/dates";

export type ReceivedSupportSharesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; shares: SupportShareReceived[] };

export type ReadSupportShareState =
	| { status: "closed" }
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; share: SupportShareReceived };

export interface ReceivedSupportSharesPageProps {
	list: ReceivedSupportSharesState;
	/** The share open to read. Each read counts, and the person sees when it happened. */
	reading: ReadSupportShareState;
	onRead: (shareId: string) => void;
	onClose: () => void;
}

/**
 * The questions that people shared with this instance admin, while each share runs. It is the one
 * place staff read a person's words, and only the words the person chose to share (ADR 0164).
 */
export function ReceivedSupportSharesPage({
	list,
	reading,
	onRead,
	onClose,
}: ReceivedSupportSharesPageProps) {
	const today = new Date(useNow());
	let body: ReactNode;
	if (list.status === "loading") {
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
						</div>
						<Skeleton className="h-8 w-16 shrink-0" />
					</div>
				))}
			</div>
		);
	} else if (list.status === "error") {
		body = (
			<QueryErrorAlert
				error={list.error}
				title="We could not load the support shares"
				onRetry={list.onRetry}
			/>
		);
	} else if (list.shares.length === 0) {
		body = (
			<EmptyState
				icon={<LifeBuoyIcon />}
				headingLevel={2}
				title="No one shares a question with you now"
				description="A person shares a question from their conversation with one admin. It shows here for 7 days, until they revoke it."
			/>
		);
	} else {
		body = (
			<ul aria-label="Support shares" className="space-y-3">
				{list.shares.map((share) => (
					<li
						key={share.id}
						className="flex flex-wrap items-start justify-between gap-4 rounded-lg border p-4"
					>
						<div className="min-w-0 space-y-1">
							<p className="text-sm font-medium">{share.sharedBy}</p>
							<p className="text-sm text-muted-foreground">
								{share.includesText ? "Diagnostics and messages" : "Diagnostics only"} · workspace{" "}
								{share.workspaceSlug} · shared {formatDayTime(share.createdAt, today)} · until{" "}
								{formatDayTime(share.expiresAt, today)}
							</p>
						</div>
						<Button variant="outline" size="sm" onClick={() => onRead(share.id)}>
							Read
						</Button>
					</li>
				))}
			</ul>
		);
	}
	return (
		<div className="max-w-3xl space-y-6">
			{body}
			<ReadShare reading={reading} onClose={onClose} />
		</div>
	);
}

function ReadShare({ reading, onClose }: { reading: ReadSupportShareState; onClose: () => void }) {
	if (reading.status === "closed") {
		return null;
	}
	return (
		<Card>
			<CardHeader>
				<CardTitle>
					{reading.status === "ready" ? `Shared by ${reading.share.sharedBy}` : "Support share"}
				</CardTitle>
				<CardDescription>
					Each read counts, and the person sees when you read it. Use the run ID to find this
					question in the server logs.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				{reading.status === "loading" && <Skeleton className="h-32 w-full rounded-lg" />}
				{reading.status === "error" && (
					<QueryErrorAlert
						error={reading.error}
						title="We could not read this share"
						onRetry={reading.onRetry}
					/>
				)}
				{reading.status === "ready" && (
					<>
						{reading.share.textWithheld && (
							<Alert>
								<AlertTitle>The messages are not shown</AlertTitle>
								<AlertDescription>
									The person deleted or changed these messages since they shared them.
								</AlertDescription>
							</Alert>
						)}
						{reading.share.turn === undefined && (
							<p className="text-sm text-muted-foreground">This question no longer exists.</p>
						)}
						<SupportShareContent
							turn={reading.share.turn}
							messages={reading.share.messages}
							asker={reading.share.sharedBy}
						/>
					</>
				)}
				<Button variant="outline" onClick={onClose}>
					Close
				</Button>
			</CardContent>
		</Card>
	);
}
