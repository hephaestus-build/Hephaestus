import { format, subDays } from "date-fns";
import { AlertCircle, History } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { CreateReviewBackfillRunRequest, ReviewBackfillRun } from "@/api/types.gen";
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
import { Spinner } from "@/components/ui/spinner";
import { ARTIFACT_KIND, artifactKindLabel, artifactKindPluralLabel } from "@/lib/artifact-kinds";
import { asDate } from "@/lib/dates";
import { formatCostUsd } from "@/lib/money";
import { hasText } from "@/lib/text";

export interface PracticeReviewBackfillProps {
	runs: ReviewBackfillRun[];
	isLoading: boolean;
	isError: boolean;
	onRetry: () => void;
	isEstimating: boolean;
	onEstimate: (request: CreateReviewBackfillRunRequest) => void;
	isUpdating: boolean;
	onConfirm: (runId: string) => void;
	onCancel: (runId: string) => void;
}

/**
 * A subset of {@link ARTIFACT_KIND}: a backfill re-reads work as it stands today, which only makes
 * sense where the work is still there to be re-read.
 */
const WORK_KINDS = [ARTIFACT_KIND.pullRequest, ARTIFACT_KIND.issue] as const;

const WORK_KIND_ITEMS = WORK_KINDS.map((kind) => ({
	value: kind as string,
	label: artifactKindPluralLabel(kind),
}));

const WINDOWS = [
	{ value: "7", label: "The last 7 days" },
	{ value: "30", label: "The last 30 days" },
	{ value: "90", label: "The last 90 days" },
	{ value: "180", label: "The last 180 days" },
] as const;

/** Each one names what stopped it and the single thing that restarts it — never just the cause. */
const PAUSE_EXPLANATIONS: Record<NonNullable<ReviewBackfillRun["pauseReason"]>, string> = {
	BUDGET_EXHAUSTED:
		"The monthly AI budget funding these reviews is used up. Nothing has been skipped — the backfill continues from where it stopped once the budget resets or the cap is raised.",
	REVIEW_MODEL_UNBOUND:
		"This workspace has no review model that can run. Nothing has been skipped — the backfill continues once one is chosen under AI models.",
	WORKSPACE_UNAVAILABLE:
		"Practice reviews are off for this workspace, or the workspace is not active. Nothing has been skipped — the backfill continues once they are back on.",
};

const nounFor = (artifactKind: string) => artifactKindLabel(artifactKind).toLowerCase();

/** The plural is the vocabulary's, not an appended "s". */
const countOf = (count: number, artifactKind: string) =>
	`${count} ${(count === 1 ? artifactKindLabel(artifactKind) : artifactKindPluralLabel(artifactKind)).toLowerCase()}`;

/**
 * `fromAt`/`toAt` are typed `Date` but arrive as ISO strings, so they go through `asDate`. Both ends
 * carry the year, because the longest windows offered can cross one.
 */
const formatWindow = (run: ReviewBackfillRun) => {
	const from = asDate(run.fromAt);
	const to = asDate(run.toAt);
	if (!from || !to) {
		return "Dates unavailable";
	}
	return `${format(from, "d MMM yyyy")} – ${format(to, "d MMM yyyy")}`;
};

/**
 * Two steps on purpose: a backfill can consume a workspace's whole monthly AI budget, so choosing a
 * range only produces an estimate, and the count and the cost are on screen before the second,
 * explicitly-worded confirmation starts any spending.
 */
export function PracticeReviewBackfill({
	runs,
	isLoading,
	isError,
	onRetry,
	isEstimating,
	onEstimate,
	isUpdating,
	onConfirm,
	onCancel,
}: PracticeReviewBackfillProps) {
	const pending = runs.find((run) => run.status === "AWAITING_CONFIRMATION");
	const active = runs.find((run) => run.status === "RUNNING" || run.status === "PAUSED");
	const history = runs.filter((run) => run.status === "COMPLETED" || run.status === "CANCELLED");

	let currentRun: ReactNode;
	if (active) {
		currentRun = <ActiveRunSection run={active} isUpdating={isUpdating} onCancel={onCancel} />;
	} else if (pending) {
		currentRun = (
			<ConfirmationSection
				run={pending}
				isUpdating={isUpdating}
				onConfirm={onConfirm}
				onCancel={onCancel}
			/>
		);
	} else {
		currentRun = (
			<EstimateSection isLoading={isLoading} isEstimating={isEstimating} onEstimate={onEstimate} />
		);
	}

	return (
		<div className="space-y-8">
			{isError ? (
				<Alert variant="destructive">
					<AlertCircle />
					<AlertTitle>Backfills couldn’t be loaded</AlertTitle>
					<AlertDescription>
						<p>Any backfill already running is unaffected — this is only about showing it here.</p>
						<Button variant="outline" size="sm" onClick={onRetry}>
							Try again
						</Button>
					</AlertDescription>
				</Alert>
			) : null}

			{currentRun}

			<HistorySection runs={history} isLoading={isLoading} />
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
	onEstimate: (request: CreateReviewBackfillRunRequest) => void;
}) {
	const [artifactKind, setArtifactKind] =
		useState<CreateReviewBackfillRunRequest["artifactKind"]>("scm.pull_request");
	const [days, setDays] = useState("30");

	const estimate = () => {
		const toAt = new Date();
		onEstimate({ artifactKind, fromAt: subDays(toAt, Number(days)), toAt });
	};

	return (
		<section className="space-y-4" aria-labelledby="backfill-estimate-heading">
			<div className="space-y-1">
				<h2 id="backfill-estimate-heading" className="text-lg font-semibold">
					Review past work
				</h2>
				<p className="text-sm text-muted-foreground">
					Reviews normally start when work happens, so anything from before this workspace was set
					up has never been measured. A backfill measures it once, as it stands today.
				</p>
			</div>
			<FieldGroup>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id="backfill-kind-label" htmlFor="backfill-kind">
							Kind of work
						</FieldLabel>
						<FieldDescription>
							One kind per backfill, so the estimate means one thing.
						</FieldDescription>
					</FieldContent>
					<Select
						items={WORK_KIND_ITEMS}
						value={artifactKind}
						onValueChange={(value) => {
							if (value) {
								setArtifactKind(value);
							}
						}}
					>
						<SelectTrigger id="backfill-kind" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="backfill-kind-label">
							{WORK_KIND_ITEMS.map((kind) => (
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
						<FieldLabel id="backfill-window-label" htmlFor="backfill-window">
							How far back
						</FieldLabel>
						<FieldDescription>Counted from when the work was opened.</FieldDescription>
					</FieldContent>
					<Select items={WINDOWS} value={days} onValueChange={(value) => setDays(value ?? days)}>
						<SelectTrigger id="backfill-window" className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="backfill-window-label">
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
					Estimate this backfill
				</Button>
				<p className="text-sm text-muted-foreground">Nothing is reviewed until you confirm.</p>
			</div>
		</section>
	);
}

function ConfirmationSection({
	run,
	isUpdating,
	onConfirm,
	onCancel,
}: {
	run: ReviewBackfillRun;
	isUpdating: boolean;
	onConfirm: (runId: string) => void;
	onCancel: (runId: string) => void;
}) {
	const noun = nounFor(run.artifactKind);
	const plural = artifactKindPluralLabel(run.artifactKind).toLowerCase();
	// A missing estimate is not an estimate of zero, and zero is a value this can legitimately carry.
	const cost = run.estimatedCostUsd === undefined ? undefined : formatCostUsd(run.estimatedCostUsd);
	const nothingToDo = run.estimatedArtifacts === 0;

	return (
		<section className="space-y-4" aria-labelledby="backfill-confirm-heading">
			<div className="space-y-1">
				<h2 id="backfill-confirm-heading" className="text-lg font-semibold">
					Confirm this backfill
				</h2>
				<p className="text-sm text-muted-foreground">{formatWindow(run)}</p>
			</div>
			<div className="grid gap-4 sm:grid-cols-2">
				<div>
					<p className="text-sm text-muted-foreground">Work to review</p>
					<p className="text-2xl font-semibold">
						{countOf(run.estimatedArtifacts, run.artifactKind)}
					</p>
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
				<AlertTitle>What a backfill does and does not do</AlertTitle>
				<AlertDescription>
					Each {noun} is measured once, as it stands now — there is no record of how it looked while
					it was being worked on. Nothing is posted on the work itself and nobody is notified:
					commenting on {plural} that are already finished would notify everyone involved about work
					nobody can act on. The measurements are kept separate from your live trends, because older
					work has been polished since and comparing the two would invent an improvement nobody
					made.
				</AlertDescription>
			</Alert>

			<div className="flex flex-wrap items-center gap-3">
				<Button
					onClick={() => onConfirm(run.id)}
					disabled={isUpdating || nothingToDo}
					aria-disabled={isUpdating || nothingToDo}
				>
					{isUpdating ? <Spinner /> : null}
					{nothingToDo
						? "Nothing to review"
						: `Review ${countOf(run.estimatedArtifacts, run.artifactKind)}`}
				</Button>
				<Button variant="outline" onClick={() => onCancel(run.id)} disabled={isUpdating}>
					Discard
				</Button>
				{nothingToDo ? (
					<p className="text-sm text-muted-foreground">
						Nothing was opened in that stretch. Discard this and try a longer one.
					</p>
				) : null}
			</div>
		</section>
	);
}

function ActiveRunSection({
	run,
	isUpdating,
	onCancel,
}: {
	run: ReviewBackfillRun;
	isUpdating: boolean;
	onCancel: (runId: string) => void;
}) {
	const walked = run.submittedCount + run.passedCount + run.failedCount;
	const total = Math.max(run.estimatedArtifacts, walked);
	const percent = total === 0 ? 100 : Math.round((walked / total) * 100);

	return (
		<section className="space-y-4" aria-labelledby="backfill-active-heading">
			<div className="space-y-1">
				<div className="flex items-center gap-2">
					<h2 id="backfill-active-heading" className="text-lg font-semibold">
						Backfill in progress
					</h2>
					<Badge variant={run.status === "PAUSED" ? "outline" : "secondary"}>
						{run.status === "PAUSED" ? "Paused" : "Running"}
					</Badge>
				</div>
				<p className="text-sm text-muted-foreground">{formatWindow(run)}</p>
			</div>
			<div className="space-y-2">
				<Progress value={percent} aria-label="Backfill progress" />
				<p className="text-sm text-muted-foreground">
					{walked} of {countOf(total, run.artifactKind)} looked at — {run.submittedCount} sent for
					review, {run.passedCount} already measured or outside your review rules.
					{run.failedCount > 0 ? ` ${run.failedCount} could not be read, and stay unmeasured.` : ""}
				</p>
			</div>

			{run.status === "PAUSED" && run.pauseReason ? (
				<Alert>
					<AlertCircle />
					<AlertTitle>Paused, not skipping</AlertTitle>
					<AlertDescription>{PAUSE_EXPLANATIONS[run.pauseReason]}</AlertDescription>
				</Alert>
			) : null}

			<Button variant="outline" onClick={() => onCancel(run.id)} disabled={isUpdating}>
				{isUpdating ? <Spinner /> : null}
				Stop this backfill
			</Button>
		</section>
	);
}

function HistorySection({ runs, isLoading }: { runs: ReviewBackfillRun[]; isLoading: boolean }) {
	let history: ReactNode;
	if (isLoading) {
		history = (
			<div className="flex justify-center py-6">
				<Spinner />
			</div>
		);
	} else if (runs.length === 0) {
		history = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<History />
					</EmptyMedia>
					<EmptyTitle>No backfills yet</EmptyTitle>
					<EmptyDescription>Past work has never been measured in this workspace.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	} else {
		history = (
			<div className="space-y-2">
				{runs.map((run) => (
					<Item key={run.id} variant="outline">
						<ItemContent>
							<ItemTitle>
								{artifactKindPluralLabel(run.artifactKind)}
								{": "}
								{formatWindow(run)}
							</ItemTitle>
							<ItemDescription>
								{run.status === "CANCELLED"
									? `Stopped after reviewing ${countOf(run.submittedCount, run.artifactKind)}.`
									: `Reviewed ${countOf(run.submittedCount, run.artifactKind)}; ${run.passedCount} needed no new measurement.${
											run.failedCount > 0
												? ` ${run.failedCount} could not be read, and stay unmeasured.`
												: ""
										}`}
							</ItemDescription>
						</ItemContent>
						<ItemActions>
							<Badge variant={run.status === "CANCELLED" ? "outline" : "secondary"}>
								{run.status === "CANCELLED" ? "Stopped" : "Finished"}
							</Badge>
						</ItemActions>
					</Item>
				))}
			</div>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="backfill-history-heading">
			<div className="space-y-1">
				<h2 id="backfill-history-heading" className="text-lg font-semibold">
					Past backfills
				</h2>
				<p className="text-sm text-muted-foreground">
					What has already been measured, and by whose decision.
				</p>
			</div>
			{history}
		</section>
	);
}
