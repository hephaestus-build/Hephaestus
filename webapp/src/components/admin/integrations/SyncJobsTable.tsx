import type { VariantProps } from "class-variance-authority";
import { formatDistanceStrict } from "date-fns";
import { AlertCircleIcon, ChevronDownIcon, HistoryIcon } from "lucide-react";

import type { SyncJob } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TablePagination } from "@/components/common/TablePagination";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import { Table, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { asDate } from "@/lib/dates";

import {
	JOB_STATUS_LABEL,
	JOB_TRIGGER_LABEL,
	JOB_TYPE_LABEL,
	jobProgress,
	phaseLabel,
	type SyncJobProgress,
} from "./sync-format";
import { TableRowsSkeleton } from "./TableRowsSkeleton";

type BadgeVariant = NonNullable<VariantProps<typeof badgeVariants>["variant"]>;

const STATUS_VARIANT: Record<SyncJob["status"], BadgeVariant> = {
	PENDING: "secondary",
	RUNNING: "default",
	SUCCEEDED: "success",
	SUCCEEDED_WITH_WARNINGS: "warning",
	FAILED: "destructive",
	CANCELLED: "secondary",
};

export interface SyncJobsTableProps {
	jobs: SyncJob[];
	isLoading?: boolean;
	isError?: boolean;
	error?: unknown;
	onRetry?: () => void;
	page?: number;
	totalPages?: number;
	onPageChange?: (page: number) => void;
}

function formatDuration(job: SyncJob): string {
	const started = asDate(job.startedAt);
	const finished = asDate(job.finishedAt);
	if (started && finished) {
		return formatDistanceStrict(finished, started);
	}
	if (started && job.status === "RUNNING") {
		return "Running…";
	}
	return "–";
}

function formatItems(job: SyncJob): string {
	// Grouped, because a backfill's counts run to seven digits and "1234567/2000000" is a number the
	// reader has to decode rather than read.
	if (job.itemsTotal != null) {
		return `${(job.itemsProcessed ?? 0).toLocaleString()}/${job.itemsTotal.toLocaleString()}`;
	}
	if (job.itemsProcessed != null) {
		return job.itemsProcessed.toLocaleString();
	}
	return "–";
}

// The expander cell plus the six data columns — the detail row spans all of them.
const COLUMN_COUNT = 7;

function hasProgressDetail(progress: SyncJobProgress): boolean {
	return (
		progress.phase != null ||
		progress.currentStep != null ||
		progress.currentRepository != null ||
		progress.unitsCompleted != null ||
		progress.unitsTotal != null
	);
}

/**
 * The last progress report the job persisted, as a labelled detail list.
 *
 * Deliberately absent: "Triggered by". `triggeredByUserId` ships on every manual job, but it is an
 * account id and this client has no endpoint that resolves one to a person — there is no account
 * lookup in the generated API at all. Rendering "Triggered by 42" would answer the accountability
 * question with a number the admin still has to go look up by hand, so the field stays unrendered
 * until the server either resolves it to a name or exposes a lookup. The Type column already says
 * *how* the job started; only *who* is missing.
 */
function JobProgressPanel({ progress }: { progress: SyncJobProgress }) {
	const { phase, currentStep, currentRepository, unitsCompleted, unitsTotal } = progress;
	const hasUnits = unitsCompleted != null || unitsTotal != null;

	return (
		<dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-1 bg-muted/30 px-2 py-2 text-sm">
			{phase && (
				<>
					<dt className="text-muted-foreground">Phase</dt>
					<dd>
						<Badge variant="secondary">{phaseLabel(phase)}</Badge>
					</dd>
				</>
			)}
			{currentStep && (
				<>
					<dt className="text-muted-foreground">Step</dt>
					{/* The step is a whole sentence and the point of the panel, so it wraps here rather than
					    truncating — the row above is where space is scarce, not this one. */}
					<dd className="wrap-anywhere">{currentStep}</dd>
				</>
			)}
			{currentRepository && (
				<>
					<dt className="text-muted-foreground">Resource</dt>
					<dd className="font-mono text-xs">{currentRepository}</dd>
				</>
			)}
			{hasUnits && (
				<>
					<dt className="text-muted-foreground">Units in phase</dt>
					<dd className="tabular-nums">
						{unitsCompleted?.toLocaleString() ?? "–"}
						{unitsTotal != null && ` / ${unitsTotal.toLocaleString()}`}
					</dd>
				</>
			)}
		</dl>
	);
}

/**
 * The header, shared verbatim by the loading and loaded states. Keeping the real `<thead>` mounted
 * while rows load is what stops the table from growing a header row on resolve.
 */
function JobsTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead className="w-0">
					<span className="sr-only">Expand details</span>
				</TableHead>
				<TableHead>Status</TableHead>
				<TableHead>Type</TableHead>
				<TableHead>Started</TableHead>
				<TableHead>Duration</TableHead>
				<TableHead className="text-right">Items</TableHead>
				<TableHead className="w-0 text-right">
					<span className="sr-only">Error</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

/** Placeholder widths sized to the copy each column actually holds; the expander/error slots promise nothing. */
const SKELETON_COLUMNS = [null, "w-16", "w-32", "w-20", "w-12", "w-14", null];

/**
 * One job, as a disclosure.
 *
 * `Collapsible` renders as a `<tbody>` and its panel as a `<tr>`: a disclosure needs its trigger and
 * panel under one root, and the only element that can legally wrap two `<tr>`s is a `<tbody>` (a table
 * may hold many). That keeps the panel inside the real table so its columns stay aligned with the
 * header — and it is the same idiom the resources table uses, rather than a second hand-rolled
 * `useState<Set>` re-implementing the ARIA wiring the primitive already ships.
 */
function JobRow({ job }: { job: SyncJob }) {
	const started = asDate(job.startedAt) ?? asDate(job.createdAt);
	const progress = jobProgress(job);
	const canExpand = hasProgressDetail(progress);

	const cells = (
		<TableRow data-job-id={job.id}>
			<TableCell className="w-0 pr-0">
				{canExpand && (
					<CollapsibleTrigger
						className="group"
						render={
							<Button variant="ghost" size="icon-sm" aria-label={`Show details for job ${job.id}`}>
								<ChevronDownIcon className="size-4 transition-transform group-data-[panel-open]:rotate-180" />
							</Button>
						}
					/>
				)}
			</TableCell>
			<TableCell>
				<Badge variant={STATUS_VARIANT[job.status]}>{JOB_STATUS_LABEL[job.status]}</Badge>
			</TableCell>
			{/* Type and trigger are one fact read as one phrase — "Reconciliation · scheduled" — not two
			    columns, since the trigger would repeat the same word down every row of a scheduled connection. */}
			<TableCell>
				{JOB_TYPE_LABEL[job.type]}
				<span className="text-muted-foreground">
					{" · "}
					{JOB_TRIGGER_LABEL[job.trigger].toLowerCase()}
				</span>
			</TableCell>
			<TableCell className="text-muted-foreground">
				<RelativeTime value={started} />
			</TableCell>
			<TableCell className="text-muted-foreground">{formatDuration(job)}</TableCell>
			<TableCell className="text-right tabular-nums text-muted-foreground">
				{formatItems(job)}
			</TableCell>
			<TableCell className="text-right">
				{job.errorSummary && (
					<HoverCard>
						<HoverCardTrigger
							render={
								<button
									type="button"
									aria-label={`Error for job ${job.id}`}
									className="inline-flex cursor-help rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
								/>
							}
						>
							<AlertCircleIcon className="size-4 text-destructive" aria-hidden />
						</HoverCardTrigger>
						<HoverCardContent className="w-80 wrap-anywhere">{job.errorSummary}</HoverCardContent>
					</HoverCard>
				)}
			</TableCell>
		</TableRow>
	);

	if (!canExpand) {
		return <tbody>{cells}</tbody>;
	}

	return (
		<Collapsible render={<tbody />}>
			{cells}
			<CollapsibleContent render={<tr data-job-detail={job.id} />}>
				<td colSpan={COLUMN_COUNT} className="border-b p-0">
					<JobProgressPanel progress={progress} />
				</td>
			</CollapsibleContent>
		</Collapsible>
	);
}

export function SyncJobsTable({
	jobs,
	isLoading = false,
	isError = false,
	error,
	onRetry,
	page,
	totalPages,
	onPageChange,
}: SyncJobsTableProps) {
	if (isError) {
		return (
			<QueryErrorAlert error={error} title="We couldn't load the job history" onRetry={onRetry} />
		);
	}

	if (isLoading) {
		return (
			<Table>
				<JobsTableHeader />
				<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={5} />
			</Table>
		);
	}

	if (jobs.length === 0) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<HistoryIcon />
					</EmptyMedia>
					<EmptyTitle>No sync jobs yet</EmptyTitle>
					<EmptyDescription>
						Initial syncs, reconciliations and manual triggers appear here as they run.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	return (
		<div className="space-y-3">
			<Table>
				<JobsTableHeader />
				{jobs.map((job) => (
					<JobRow key={job.id} job={job} />
				))}
			</Table>

			{page != null && totalPages != null && onPageChange != null && (
				<TablePagination
					className="justify-end"
					page={page}
					totalPages={totalPages}
					onPageChange={onPageChange}
				/>
			)}
		</div>
	);
}
