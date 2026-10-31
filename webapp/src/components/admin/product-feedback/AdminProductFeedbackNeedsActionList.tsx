import { CircleCheck } from "lucide-react";
import { useId } from "react";

import type { ProductFeedbackNeedsAction } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";

import { FEEDBACK_KIND_DEFS } from "./feedback-kind-defs";

export type AdminProductFeedbackNeedsActionState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; items: ProductFeedbackNeedsAction[] };

export interface AdminProductFeedbackNeedsActionListProps {
	state: AdminProductFeedbackNeedsActionState;
	/** Reports with a retry in flight. */
	pendingIds: ReadonlySet<string>;
	onRetryFiling: (item: ProductFeedbackNeedsAction) => void;
}

const ROUTE_WORDS: Record<ProductFeedbackNeedsAction["route"], string> = {
	PUBLIC: "a public issue",
	PRIVATE: "a private issue",
	EMAIL: "the admin email",
};

const SKELETON_ROWS = 2;

/**
 * Reports that failed to file and wait for an admin. The admin email already carries each one, so
 * Retry is the only action: it sends the report through filing again.
 */
export function AdminProductFeedbackNeedsActionList({
	state,
	pendingIds,
	onRetryFiling,
}: AdminProductFeedbackNeedsActionListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the reports that need action"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<ul className="flex flex-col divide-y rounded-lg border" aria-busy>
				{Array.from({ length: SKELETON_ROWS }, (_, index) => (
					<li key={index} className="flex flex-col gap-2 p-3">
						<Skeleton className="h-5 w-40" />
						<Skeleton className="h-4 w-2/3" />
					</li>
				))}
			</ul>
		);
	}
	if (state.items.length === 0) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<CircleCheck />
					</EmptyMedia>
					<EmptyTitle>Nothing needs action</EmptyTitle>
					<EmptyDescription>A report that fails to file on GitHub appears here.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<ul className="flex flex-col divide-y rounded-lg border">
			{state.items.map((item) => (
				<NeedsActionRow
					key={item.id}
					item={item}
					pending={pendingIds.has(item.id)}
					onRetryFiling={onRetryFiling}
				/>
			))}
		</ul>
	);
}

function NeedsActionRow({
	item,
	pending,
	onRetryFiling,
}: {
	item: ProductFeedbackNeedsAction;
	pending: boolean;
	onRetryFiling: (item: ProductFeedbackNeedsAction) => void;
}) {
	const id = useId();
	return (
		<li className="flex flex-wrap items-center justify-between gap-3 p-3 text-sm">
			<div id={`${id}-summary`} className="flex min-w-0 flex-col gap-1">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<StatusBadge def={FEEDBACK_KIND_DEFS[item.kind]} />
					<span className="text-muted-foreground">
						Sent <RelativeTime value={item.createdAt} /> · stopped{" "}
						<RelativeTime value={item.stoppedAt} />
					</span>
				</div>
				<p className="break-words">
					Stopped because of: {item.reason}. It was going to {ROUTE_WORDS[item.route]}
					{hasText(item.repository) ? ` in ${item.repository}` : ""}.
				</p>
			</div>
			<Button
				type="button"
				variant="outline"
				size="sm"
				disabled={pending}
				aria-describedby={`${id}-summary`}
				onClick={() => onRetryFiling(item)}
			>
				{pending && <Spinner />}
				{pending ? "Retrying…" : "Retry"}
			</Button>
		</li>
	);
}
