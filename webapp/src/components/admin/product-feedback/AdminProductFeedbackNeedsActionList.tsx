import { CircleCheck } from "lucide-react";
import { type ReactNode, useId } from "react";
import { definitionFor } from "@/components/common/status-def";

import type { ProductFeedbackCleanupAction, ProductFeedbackNeedsAction } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
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
	| {
			status: "ready";
			items: ProductFeedbackNeedsAction[];
			/** Erased reports whose GitHub issues only a maintainer can finish removing. */
			cleanups: ProductFeedbackCleanupAction[];
	  };

export interface AdminProductFeedbackNeedsActionListProps {
	state: AdminProductFeedbackNeedsActionState;
	/** Reports with a retry, and cleanup steps with a confirmation, in flight. */
	pendingIds: ReadonlySet<string>;
	onRetryFiling: (item: ProductFeedbackNeedsAction) => void;
	onConfirmCleanup: (cleanup: ProductFeedbackCleanupAction) => void;
}

const ROUTE_WORDS: Record<ProductFeedbackNeedsAction["route"], string> = {
	PUBLIC: "a public issue",
	PRIVATE: "a private issue",
	ADMINS: "Needs you in Reports",
};

const SKELETON_ROWS = 2;

/**
 * What waits for an admin: reports that failed to file, with Retry, and erased reports whose GitHub
 * issue a maintainer must finish removing, with Confirm done. Confirming deletes the issue locator,
 * so it asks first.
 */
export function AdminProductFeedbackNeedsActionList({
	state,
	pendingIds,
	onRetryFiling,
	onConfirmCleanup,
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
	if (state.items.length === 0 && state.cleanups.length === 0) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<CircleCheck />
					</EmptyMedia>
					<EmptyTitle>Nothing needs action</EmptyTitle>
					<EmptyDescription>
						A report that fails to file on GitHub appears here, and so does an erased report whose
						issue needs a maintainer.
					</EmptyDescription>
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
			{state.cleanups.map((cleanup) => (
				<CleanupRow
					key={cleanup.id}
					cleanup={cleanup}
					pending={pendingIds.has(cleanup.id)}
					onConfirm={onConfirmCleanup}
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
					<StatusBadge def={definitionFor(FEEDBACK_KIND_DEFS, item.kind)} />
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

function CleanupRow({
	cleanup,
	pending,
	onConfirm,
}: {
	cleanup: ProductFeedbackCleanupAction;
	pending: boolean;
	onConfirm: (cleanup: ProductFeedbackCleanupAction) => void;
}) {
	const id = useId();
	const issue = hasText(cleanup.issueUrl) ? (
		<InlineLink href={cleanup.issueUrl} external>
			{cleanup.repository}#{cleanup.issueNumber}
		</InlineLink>
	) : undefined;
	let task: ReactNode;
	if (cleanup.step === "EDIT_HISTORY") {
		task = (
			<>
				The app removed the text of {issue ?? cleanup.repository} and closed it, but its edit
				history still shows the erased text. Delete the issue, or delete each earlier revision of
				it.
			</>
		);
	} else if (issue === undefined) {
		task = (
			<>
				The app could not tell whether an issue exists in {cleanup.repository}. Look for an issue
				whose body contains <code className="break-all">{cleanup.marker}</code> and remove it if it
				exists.
			</>
		);
	} else {
		task = (
			<>
				The app could not finish removing {issue} within 30 days. Transfer it to a private
				repository, remove its text and edit history, and close it, or delete the issue.
			</>
		);
	}
	return (
		<li className="flex flex-wrap items-center justify-between gap-3 p-3 text-sm">
			<div id={`${id}-summary`} className="flex min-w-0 flex-col gap-1">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<Badge variant="outline">Erased report</Badge>
					<span className="text-muted-foreground">
						Erased <RelativeTime value={cleanup.erasedAt} /> · waiting since{" "}
						<RelativeTime value={cleanup.since} />
					</span>
				</div>
				<p className="break-words">{task}</p>
			</div>
			<AlertDialog>
				<AlertDialogTrigger
					render={
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={pending}
							aria-describedby={`${id}-summary`}
						>
							{pending && <Spinner />}
							{pending ? "Confirming…" : "Confirm done"}
						</Button>
					}
				/>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Is the erased text gone from GitHub?</AlertDialogTitle>
						<AlertDialogDescription>
							After you confirm, Hephaestus no longer keeps the location of this issue and cannot
							show it again.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction onClick={() => onConfirm(cleanup)}>Confirm done</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</li>
	);
}
