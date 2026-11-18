import { ChevronDown, Inbox } from "lucide-react";
import { definitionFor } from "@/components/common/status-def";

import type { FeedbackItem } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TablePagination } from "@/components/common/TablePagination";
import { DraftingProgress } from "@/components/product-feedback/DraftingProgress";
import { FiledIssue } from "@/components/product-feedback/FiledIssue";
import { PublicHoldNotice } from "@/components/product-feedback/PublicHoldNotice";
import {
	REPORT_STATE_DEFS,
	REPORT_VISIBILITY_DEFS,
	reportVisibility,
} from "@/components/product-feedback/report-state-defs";
import { ReportStatusLine } from "@/components/product-feedback/ReportStatusLine";
import { reasonSentence } from "@/components/product-feedback/sharing-copy";
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
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import { FEEDBACK_KIND_DEFS } from "./feedback-kind-defs";

export type FeedbackStatusFilter = "OPEN" | "IN_FLIGHT" | "ON_GITHUB" | "RESOLVED" | "ALL";

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
	/** Items on their way to GitHub. */
	filingIds?: ReadonlySet<string>;
	onTriage: (item: FeedbackItem, resolved: boolean) => void;
	/** Sends a saved report to GitHub; offered only where the item says it can go. */
	onFile: (item: FeedbackItem) => void;
}

const EMPTY_COPY: Record<FeedbackStatusFilter, { title: string; description: string }> = {
	OPEN: { title: "Nothing waits for you", description: "Reports that need an admin appear here." },
	IN_FLIGHT: {
		title: "Nothing on its way",
		description: "Reports appear here while Hephaestus drafts and files them.",
	},
	ON_GITHUB: {
		title: "No open issues",
		description: "Filed reports with an open issue appear here.",
	},
	RESOLVED: {
		title: "Nothing closed yet",
		description: "Reports you resolved and closed issues appear here.",
	},
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
export function AdminFeedbackList({
	state,
	pendingIds,
	filingIds,
	onTriage,
	onFile,
}: AdminFeedbackListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load feedback"
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
			<Empty variant="outlined">
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
						<FeedbackCard
							item={item}
							pending={pendingIds.has(item.id)}
							filing={filingIds?.has(item.id) === true}
							onTriage={onTriage}
							onFile={onFile}
						/>
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
	filing: boolean;
	onTriage: (item: FeedbackItem, resolved: boolean) => void;
	onFile: (item: FeedbackItem) => void;
}

function FeedbackCard({ item, pending, filing, onTriage, onFile }: FeedbackCardProps) {
	const resolved = item.resolvedAt !== undefined;
	const visibility = definitionFor(REPORT_VISIBILITY_DEFS, reportVisibility(item));
	let triageLabel = resolved ? "Reopen" : "Mark resolved";
	if (pending) {
		triageLabel = "Saving…";
	}
	return (
		<Card>
			<CardHeader className="flex flex-col gap-2">
				<div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
					<span className="font-medium">{item.account?.displayName ?? "Deleted account"}</span>
					{hasText(item.account?.email) && (
						<a
							href={`mailto:${item.account.email}`}
							className="break-all underline underline-offset-4"
						>
							{item.account.email}
						</a>
					)}
					<span className="text-muted-foreground">
						{item.workspace?.displayName ?? "No workspace"}
					</span>
				</div>
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<StatusBadge def={definitionFor(FEEDBACK_KIND_DEFS, item.kind)} />
					<StatusBadge def={definitionFor(REPORT_STATE_DEFS, item.state)} />
					<StatusBadge def={visibility} />
					<ReportStatusLine report={item} />
				</div>
			</CardHeader>
			<CardContent className="flex flex-col gap-3">
				<p className="break-words whitespace-pre-wrap">{item.message}</p>
				{item.state === "DRAFTING" && (
					<DraftingProgress
						since={item.draftingSince}
						deadline={item.draftDeadline}
						waitingForModel={item.waitingForModel}
						wantsPublic={item.route === "PUBLIC"}
						reader="admin"
					/>
				)}
				<PublicHoldNotice
					state={item.state}
					filedAfter={item.filedAfter}
					wantsPublic={item.route === "PUBLIC"}
					reader="admin"
				/>
				{item.state === "HELD" && hasText(item.draftTitle) && (
					<HeldDraft title={item.draftTitle} description={item.draftDescription} />
				)}
				{item.route === "PRIVATE" && item.allowPublic && (
					<p className="text-muted-foreground">The reporter allows a public issue.</p>
				)}
				{hasText(item.reason) && (
					<p className="text-muted-foreground">{reasonSentence(item.reason, item.allowPublic)}</p>
				)}
				{hasText(item.issueTitle) && hasText(item.issueText) && (
					<FiledIssue title={item.issueTitle} text={item.issueText} />
				)}
				<ReportDetails item={item} />
			</CardContent>
			{/* A filed report has nothing to press, so it shows no empty footer. */}
			{(resolved || item.canFile || item.canTriage) && (
				<CardFooter className="flex flex-wrap items-center justify-between gap-2">
					{resolved ? (
						<span className="text-muted-foreground">
							Resolved by {item.resolvedBy?.displayName ?? "a deleted account"} ·{" "}
							<RelativeTime value={item.resolvedAt} />
						</span>
					) : (
						<span />
					)}
					<div className="flex flex-wrap items-center gap-2">
						{item.canFile && (
							<AlertDialog>
								<AlertDialogTrigger
									render={
										<Button
											pending={filing}
											type="button"
											variant="outline"
											size="sm"
											disabled={pending || filing}
										>
											{filing ? "Filing…" : "File on GitHub"}
										</Button>
									}
								/>
								<AlertDialogContent>
									<AlertDialogHeader>
										<AlertDialogTitle>File this report on GitHub?</AlertDialogTitle>
										<AlertDialogDescription>
											{item.savedBeforeDisclosure
												? "It becomes a private issue with the sender’s text as written. No AI model reads it."
												: "An AI model drafts a private issue from it."}{" "}
											The text leaves this instance, and the sender’s name stays here. You cannot
											take this back.
										</AlertDialogDescription>
									</AlertDialogHeader>
									<AlertDialogFooter>
										<AlertDialogCancel>Cancel</AlertDialogCancel>
										<AlertDialogAction onClick={() => onFile(item)}>
											File on GitHub
										</AlertDialogAction>
									</AlertDialogFooter>
								</AlertDialogContent>
							</AlertDialog>
						)}
						{item.canTriage && (
							<Button
								pending={pending}
								type="button"
								variant="outline"
								size="sm"
								disabled={pending || filing}
								onClick={() => onTriage(item, !resolved)}
							>
								{triageLabel}
							</Button>
						)}
					</div>
				</CardFooter>
			)}
		</Card>
	);
}

/**
 * The model's draft while the report waits to become public: the title in view, the text on request.
 * `FiledIssue` puts the title inside its disclosure too, so it does not fit here.
 */
function HeldDraft({ title, description }: { title: string; description?: string }) {
	return (
		<div className="flex flex-col gap-1 rounded-lg border p-3">
			<p className="text-sm text-muted-foreground">The drafted issue</p>
			<p className="font-medium break-words">{title}</p>
			{hasText(description) && (
				<Collapsible>
					<CollapsibleTrigger
						aria-label={`The drafted text: ${title}`}
						render={<Button variant="ghost" size="sm" className="group" />}
					>
						The drafted text
						<ChevronDown
							data-icon="inline-end"
							aria-hidden
							className="transition-transform group-data-[panel-open]:rotate-180"
						/>
					</CollapsibleTrigger>
					<CollapsibleContent>
						<p className="mt-1 text-sm break-words whitespace-pre-wrap">{description}</p>
					</CollapsibleContent>
				</Collapsible>
			)}
		</div>
	);
}

/** Where the report was sent from. The browser string is long and has few spaces, so it wraps anywhere. */
function ReportDetails({ item }: { item: FeedbackItem }) {
	if (!hasText(item.pagePath) && !hasText(item.userAgent) && !hasText(item.appVersion)) {
		return null;
	}
	return (
		<dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs text-muted-foreground">
			{hasText(item.pagePath) && (
				<>
					<dt>Page</dt>
					<dd className="min-w-0">
						<code className="break-all">{item.pagePath}</code>
					</dd>
				</>
			)}
			{hasText(item.appVersion) && (
				<>
					<dt>Version</dt>
					<dd className="min-w-0">Hephaestus {item.appVersion}</dd>
				</>
			)}
			{hasText(item.userAgent) && (
				<>
					<dt>Browser</dt>
					<dd className="min-w-0 break-all">{item.userAgent}</dd>
				</>
			)}
		</dl>
	);
}
