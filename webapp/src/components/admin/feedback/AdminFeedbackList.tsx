import { Inbox } from "lucide-react";

import type { FeedbackItem } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TablePagination } from "@/components/common/TablePagination";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

import { FEEDBACK_KIND_DEFS } from "./feedback-kind-defs";

export type FeedbackStatusFilter = "OPEN" | "RESOLVED" | "ALL";

export type AdminFeedbackListState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			items: FeedbackItem[];
			/** Which slice is showing, which decides what an empty page means. */
			filter: FeedbackStatusFilter;
			page: number;
			totalPages: number;
			onPageChange: (page: number) => void;
	  };

export interface AdminFeedbackListProps {
	state: AdminFeedbackListState;
	/** Items with a triage change in flight. */
	pendingIds: ReadonlySet<string>;
	onTriage: (item: FeedbackItem, resolved: boolean) => void;
}

const EMPTY_COPY: Record<FeedbackStatusFilter, { title: string; description: string }> = {
	OPEN: { title: "Inbox zero", description: "Nothing is waiting." },
	RESOLVED: { title: "No resolved feedback yet", description: "Resolved feedback moves here." },
	ALL: {
		title: "No feedback yet",
		description: "Members send ideas, bug reports and feedback from the header.",
	},
};

const SKELETON_ROWS = 3;

/**
 * Feedback and bug reports, one card each. Resolving is bookkeeping for the administrators —
 * nobody is notified — so it is one press and reversible.
 */
export function AdminFeedbackList({ state, pendingIds, onTriage }: AdminFeedbackListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Feedback couldn't be loaded"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<ul className="flex flex-col gap-3" aria-busy>
				{Array.from({ length: SKELETON_ROWS }, (_, index) => (
					<li key={index}>
						<Card>
							<CardHeader>
								<Skeleton className="h-5 w-40" />
							</CardHeader>
							<CardContent className="flex flex-col gap-2">
								<Skeleton className="h-4 w-full" />
								<Skeleton className="h-4 w-3/4" />
							</CardContent>
						</Card>
					</li>
				))}
			</ul>
		);
	}
	if (state.items.length === 0) {
		const copy = EMPTY_COPY[state.filter];
		return (
			<Empty className="rounded-md border">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<Inbox />
					</EmptyMedia>
					<EmptyTitle>{copy.title}</EmptyTitle>
					<EmptyDescription>{copy.description}</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<div className="flex flex-col gap-4">
			<ul className="flex flex-col gap-3">
				{state.items.map((item) => (
					<li key={item.id}>
						<FeedbackCard item={item} pending={pendingIds.has(item.id)} onTriage={onTriage} />
					</li>
				))}
			</ul>
			<TablePagination
				page={state.page}
				totalPages={state.totalPages}
				onPageChange={state.onPageChange}
			/>
		</div>
	);
}

interface FeedbackCardProps {
	item: FeedbackItem;
	pending: boolean;
	onTriage: (item: FeedbackItem, resolved: boolean) => void;
}

function FeedbackCard({ item, pending, onTriage }: FeedbackCardProps) {
	const resolved = item.resolvedAt !== undefined;
	const context = [
		item.pagePath && (
			<code key="page" className="break-all">
				{item.pagePath}
			</code>
		),
		item.userAgent && (
			<span key="agent" className="break-words">
				{item.userAgent}
			</span>
		),
		item.appVersion && <span key="version">Hephaestus {item.appVersion}</span>,
	].filter(Boolean);
	return (
		<Card>
			<CardHeader className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
				<StatusBadge def={FEEDBACK_KIND_DEFS[item.kind]} />
				<span className="font-medium" title={item.account?.email}>
					{item.account?.displayName ?? "Deleted account"}
				</span>
				<span className="text-muted-foreground">
					{item.workspace?.displayName ?? "No workspace"}
				</span>
				<RelativeTime value={item.createdAt} className="text-muted-foreground" />
			</CardHeader>
			<CardContent className="flex flex-col gap-3">
				<p className="break-words whitespace-pre-wrap">{item.message}</p>
				{context.length > 0 && (
					<p className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">{context}</p>
				)}
			</CardContent>
			<CardFooter className="flex flex-wrap items-center justify-between gap-2 text-sm">
				{resolved ? (
					<span className="text-muted-foreground">
						Resolved by {item.resolvedBy?.displayName ?? "a deleted account"} ·{" "}
						<RelativeTime value={item.resolvedAt} />
					</span>
				) : (
					<span className="text-muted-foreground">Open</span>
				)}
				<Button
					type="button"
					variant="outline"
					size="sm"
					disabled={pending}
					onClick={() => onTriage(item, !resolved)}
				>
					{pending && <Spinner className="size-4" />}
					{pending ? "Saving…" : resolved ? "Reopen" : "Mark resolved"}
				</Button>
			</CardFooter>
		</Card>
	);
}
