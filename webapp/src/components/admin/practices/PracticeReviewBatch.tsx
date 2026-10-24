import { format, subDays } from "date-fns";
import { AlertCircle, History } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { CreateReviewBatchRequest, ReviewBatch } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Progress } from "@/components/ui/progress";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";
import { ITEM_KIND, itemKindLabel, itemKindNoun } from "@/lib/item-kinds";
import { formatCostUsd } from "@/lib/money";
import { hasText } from "@/lib/text";

export type ReviewBatchStatusChange = "RUNNING" | "PAUSED" | "CANCELLED";

export interface PracticeReviewBatchProps {
	batches: ReviewBatch[];
	isLoading: boolean;
	isError: boolean;
	onRetry: () => void;
	isEstimating: boolean;
	onEstimate: (request: CreateReviewBatchRequest) => void;
	isUpdating: boolean;
	onChangeStatus: (batch: ReviewBatch, status: ReviewBatchStatusChange) => void;
}

/**
 * A subset of {@link ITEM_KIND}: a review of past work reads the work as it stands today, which only
 * makes sense where the work is still there to be read.
 */
const ITEM_KINDS = [ITEM_KIND.pullRequest, ITEM_KIND.issue] as const;

const ITEM_KIND_ITEMS = ITEM_KINDS.map((kind) => ({
	value: kind as string,
	label: itemKindLabel(kind, 2),
}));

const WINDOWS = [
	{ value: "7", label: "The last 7 days" },
	{ value: "30", label: "The last 30 days" },
	{ value: "90", label: "The last 90 days" },
	{ value: "180", label: "The last 180 days" },
] as const;

const PAUSE_EXPLANATIONS: Record<NonNullable<ReviewBatch["pauseReason"]>, string> = {
	PAUSED_BY_ADMIN:
		"A workspace admin paused this review. Resume it to continue from where it stopped.",
	BUDGET_EXHAUSTED:
		"The monthly AI budget funding these reviews is used up. Nothing has been skipped. The review continues from where it stopped once the budget resets or the cap is raised.",
	REVIEW_MODEL_UNBOUND:
		"This workspace has no review model that can run. Nothing has been skipped. The review continues once one is chosen under AI models.",
	WORKSPACE_UNAVAILABLE:
		"Practice reviews are off for this workspace, or the workspace is not active. Nothing has been skipped. The review continues once they are back on.",
};

const countOf = (count: number, itemKind: string) => `${count} ${itemKindNoun(itemKind, count)}`;

const formatWindow = (batch: ReviewBatch) => {
	const from = asDate(batch.from);
	const to = asDate(batch.to);
	if (!from || !to) {
		return "Dates unavailable";
	}
	return `${format(from, "d MMM yyyy")} – ${format(to, "d MMM yyyy")}`;
};

// Missing estimates are unknown; a measured zero remains a valid estimate.
const formatEstimate = (value: string | null) =>
	value === null ? undefined : formatCostUsd(Number(value));

// Require estimate confirmation before a review can consume the workspace's AI budget.
export function PracticeReviewBatch({
	batches,
	isLoading,
	isError,
	onRetry,
	isEstimating,
	onEstimate,
	isUpdating,
	onChangeStatus,
}: PracticeReviewBatchProps) {
	const pastWork = batches.filter((batch) => batch.origin === "BACKFILL");
	const pending = pastWork.find((batch) => batch.status === "AWAITING_CONFIRMATION");
	const active = pastWork.find((batch) => batch.status === "RUNNING" || batch.status === "PAUSED");
	const history = pastWork.filter(
		(batch) => batch.status === "COMPLETED" || batch.status === "CANCELLED",
	);
	// A first read that failed says nothing about past reviews or a running one: the error stands in
	// for the history, and a new estimate waits until the page knows nothing is running.
	const unknown = isError && batches.length === 0;

	let current: ReactNode;
	if (active) {
		current = (
			<ActiveBatchSection batch={active} isUpdating={isUpdating} onChangeStatus={onChangeStatus} />
		);
	} else if (pending) {
		current = (
			<ConfirmationSection
				batch={pending}
				isUpdating={isUpdating}
				onChangeStatus={onChangeStatus}
			/>
		);
	} else {
		current = (
			<EstimateSection
				isLoading={isLoading || unknown}
				isEstimating={isEstimating}
				onEstimate={onEstimate}
			/>
		);
	}

	return (
		<div className="space-y-8">
			{isError ? (
				<Alert variant="destructive">
					<AlertCircle />
					<AlertTitle>We could not load reviews of past work</AlertTitle>
					<AlertDescription>
						<p>A running review is not affected. Check your connection, then try again.</p>
						<Button variant="outline" size="sm" onClick={onRetry}>
							Retry
						</Button>
					</AlertDescription>
				</Alert>
			) : null}

			{current}

			{!unknown && <HistorySection batches={history} isLoading={isLoading} />}
		</div>
	);
}

function EstimateSection({
	isLoading,
	isEstimating,
	onEstimate,
}: {
	isLoading: boolean;
	isEstimating: boolean;
	onEstimate: (request: CreateReviewBatchRequest) => void;
}) {
	const [itemKind, setItemKind] = useState<string>(ITEM_KIND.pullRequest);
	const [days, setDays] = useState("30");

	const estimate = () => {
		const to = new Date();
		onEstimate({ itemKind, from: subDays(to, Number(days)), to, budgetUsd: null });
	};

	return (
		<section className="space-y-4" aria-labelledby="batch-estimate-heading">
			<div className="space-y-1">
				<h2 id="batch-estimate-heading" className="text-lg font-semibold">
					Review past work
				</h2>
				<p className="text-sm text-muted-foreground">
					Reviews normally start when work happens, so anything from before this workspace was set
					up has never been reviewed. This reviews it once, as it stands today.
				</p>
			</div>
			<FieldGroup>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="batch-kind-label" htmlFor="batch-kind">
							Kind of work
						</FieldLabel>
						<FieldDescription>
							One kind at a time, so the estimate means one thing.
						</FieldDescription>
					</FieldContent>
					<Select
						items={ITEM_KIND_ITEMS}
						value={itemKind}
						onValueChange={(value) => setItemKind(value ?? itemKind)}
					>
						<SelectTrigger id="batch-kind" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="batch-kind-label">
							{ITEM_KIND_ITEMS.map((kind) => (
								<SelectItem key={kind.value} value={kind.value}>
									{kind.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
			</FieldGroup>

			<FieldGroup>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="batch-window-label" htmlFor="batch-window">
							How far back
						</FieldLabel>
						<FieldDescription>Counted from when the work was opened.</FieldDescription>
					</FieldContent>
					<Select items={WINDOWS} value={days} onValueChange={(value) => setDays(value ?? days)}>
						<SelectTrigger id="batch-window" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="batch-window-label">
							{WINDOWS.map((window) => (
								<SelectItem key={window.value} value={window.value}>
									{window.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
			</FieldGroup>

			<div className="flex items-center gap-3">
				<Button onClick={estimate} disabled={isEstimating || isLoading}>
					{isEstimating ? <Spinner /> : null}
					Estimate this review
				</Button>
				<p className="text-sm text-muted-foreground">Nothing is reviewed until you confirm.</p>
			</div>
		</section>
	);
}

function ConfirmationSection({
	batch,
	isUpdating,
	onChangeStatus,
}: {
	batch: ReviewBatch;
	isUpdating: boolean;
	onChangeStatus: PracticeReviewBatchProps["onChangeStatus"];
}) {
	const noun = itemKindNoun(batch.itemKind, 1);
	const plural = itemKindNoun(batch.itemKind, 2);
	const cost = formatEstimate(batch.estimatedCostUsd);
	const nothingToDo = batch.estimatedItems === 0;

	return (
		<section className="space-y-4" aria-labelledby="batch-confirm-heading">
			<div className="space-y-1">
				<h2 id="batch-confirm-heading" className="text-lg font-semibold">
					Confirm this review of past work
				</h2>
				<p className="text-sm text-muted-foreground">{formatWindow(batch)}</p>
			</div>
			<div className="grid gap-4 sm:grid-cols-2">
				<div>
					<p className="text-sm text-muted-foreground">Work to review</p>
					<p className="text-2xl font-semibold">{countOf(batch.estimatedItems, batch.itemKind)}</p>
				</div>
				<div>
					<p className="text-sm text-muted-foreground">Estimated AI spend</p>
					<p className="text-2xl font-semibold">{cost ?? "Unknown"}</p>
					{hasText(cost) ? null : (
						<p className="text-sm text-muted-foreground">
							This workspace has no priced reviews yet, so there is nothing to base an estimate on.
						</p>
					)}
				</div>
			</div>

			<Alert>
				<AlertCircle />
				<AlertTitle>What a review of past work does and does not do</AlertTitle>
				<AlertDescription>
					Each {noun} is reviewed once, as it stands now. There is no record of how it looked while
					it was being worked on. Nothing is posted on the work itself and nobody is notified.
					Commenting on {plural} that are already finished would notify everyone involved about work
					nobody can act on. The observations it records are kept separate from your live trends,
					because older work has been polished since. A comparison of the two would invent an
					improvement nobody made.
				</AlertDescription>
			</Alert>

			<div className="flex flex-wrap items-center gap-3">
				<Button
					onClick={() => onChangeStatus(batch, "RUNNING")}
					disabled={isUpdating || nothingToDo}
					aria-disabled={isUpdating || nothingToDo}
				>
					{isUpdating ? <Spinner /> : null}
					{nothingToDo
						? "Nothing to review"
						: `Review ${countOf(batch.estimatedItems, batch.itemKind)}`}
				</Button>
				<Button
					variant="outline"
					onClick={() => onChangeStatus(batch, "CANCELLED")}
					disabled={isUpdating}
				>
					Discard estimate
				</Button>
				{nothingToDo ? (
					<p className="text-sm text-muted-foreground">
						Nothing was opened in that stretch. Discard this estimate and try a longer one.
					</p>
				) : null}
			</div>
		</section>
	);
}

function ActiveBatchSection({
	batch,
	isUpdating,
	onChangeStatus,
}: {
	batch: ReviewBatch;
	isUpdating: boolean;
	onChangeStatus: PracticeReviewBatchProps["onChangeStatus"];
}) {
	const paused = batch.status === "PAUSED";
	const walked = batch.submittedCount + batch.skippedCount + batch.failedCount;
	const total = Math.max(batch.estimatedItems, walked);
	const percent = total === 0 ? 100 : Math.round((walked / total) * 100);

	return (
		<section className="space-y-4" aria-labelledby="batch-active-heading">
			<div className="space-y-1">
				<div className="flex items-center gap-2">
					<h2 id="batch-active-heading" className="text-lg font-semibold">
						Reviewing past work
					</h2>
					<Badge variant={paused ? "outline" : "secondary"}>{paused ? "Paused" : "Running"}</Badge>
				</div>
				<p className="text-sm text-muted-foreground">{formatWindow(batch)}</p>
			</div>
			<div className="space-y-2">
				<Progress value={percent} aria-label="Progress of the review of past work" />
				<p className="text-sm text-muted-foreground">
					{walked} of {countOf(total, batch.itemKind)} looked at. {batch.submittedCount} sent for
					review, {batch.skippedCount} already reviewed or outside the review scope.
					{batch.failedCount > 0
						? ` ${batch.failedCount} could not be read, so were not reviewed.`
						: ""}
				</p>
			</div>

			{paused && batch.pauseReason !== null ? (
				<Alert>
					<AlertCircle />
					<AlertTitle>Paused, not skipping</AlertTitle>
					<AlertDescription>{PAUSE_EXPLANATIONS[batch.pauseReason]}</AlertDescription>
				</Alert>
			) : null}

			<div className="flex flex-wrap items-center gap-3">
				<Button
					variant="outline"
					onClick={() => onChangeStatus(batch, paused ? "RUNNING" : "PAUSED")}
					disabled={isUpdating}
				>
					{isUpdating ? <Spinner /> : null}
					{paused ? "Resume" : "Pause"}
				</Button>
				<Button
					variant="ghost"
					onClick={() => onChangeStatus(batch, "CANCELLED")}
					disabled={isUpdating}
				>
					Stop this review
				</Button>
			</div>
		</section>
	);
}

function HistorySection({ batches, isLoading }: { batches: ReviewBatch[]; isLoading: boolean }) {
	let history: ReactNode;
	if (isLoading) {
		history = (
			<div className="space-y-2" aria-hidden>
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-16 w-full" />
			</div>
		);
	} else if (batches.length === 0) {
		history = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<History />
					</EmptyMedia>
					<EmptyTitle>No past work reviewed yet</EmptyTitle>
					<EmptyDescription>
						Finished and stopped reviews of past work appear here.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else {
		history = (
			<div className="space-y-2">
				{batches.map((batch) => (
					<Item key={batch.id} variant="outline">
						<ItemContent>
							<ItemTitle>
								{itemKindLabel(batch.itemKind, 2)}
								{": "}
								{formatWindow(batch)}
							</ItemTitle>
							<ItemDescription>
								{batch.status === "CANCELLED"
									? `Stopped after reviewing ${countOf(batch.submittedCount, batch.itemKind)}.`
									: `Reviewed ${countOf(batch.submittedCount, batch.itemKind)}. ${batch.skippedCount} needed no new observation.${
											batch.failedCount > 0
												? ` ${batch.failedCount} could not be read, so were not reviewed.`
												: ""
										}`}
							</ItemDescription>
						</ItemContent>
						<ItemActions>
							<Badge variant={batch.status === "CANCELLED" ? "outline" : "secondary"}>
								{batch.status === "CANCELLED" ? "Stopped" : "Finished"}
							</Badge>
						</ItemActions>
					</Item>
				))}
			</div>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="batch-history-heading">
			<div className="space-y-1">
				<h2 id="batch-history-heading" className="text-lg font-semibold">
					Earlier reviews of past work
				</h2>
				<p className="text-sm text-muted-foreground">What earlier reviews of past work covered.</p>
			</div>
			{history}
		</section>
	);
}
