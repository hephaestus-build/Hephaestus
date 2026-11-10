import type { PracticeScriptRun, ReviewPractice } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { definitionFor } from "@/components/common/status-def";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { PracticeScriptFixLink } from "@/components/practice-trace/PracticeScriptFixLink";
import {
	UNKNOWN_SCRIPT_RUN_DEF,
	PRACTICE_SCRIPT_RUN_STATUS_DEFS,
	practiceScriptFix,
} from "@/components/practice-vocabulary/practice-script-run-status-defs";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { hasText } from "@/lib/text";

import { formatNumber } from "@/lib/numbers";
import { practiceLevel } from "./review-levels";

const seconds = new Intl.NumberFormat("en-US", {
	minimumFractionDigits: 1,
	maximumFractionDigits: 1,
});

function formatScriptTime(durationMs: number): string {
	if (durationMs < 100) {
		return "< 0.1 s";
	}
	const tenths = Math.round(durationMs / 100);
	if (tenths < 600) {
		return `${seconds.format(tenths / 10)} s`;
	}
	const total = Math.round(durationMs / 1000);
	return `${formatNumber(Math.floor(total / 60))} min ${total % 60} s`;
}

export interface ReviewPracticeScriptTableProps {
	workspaceSlug: string;
	practices: ReviewPractice[];
}

export function ReviewPracticeScriptTable({
	workspaceSlug,
	practices,
}: ReviewPracticeScriptTableProps) {
	return (
		<Table bordered className="sm:min-w-160">
			<caption className="sr-only">How each practice script ran in this review</caption>
			<TableHeader>
				<TableRow variant="static">
					<TableHead scope="col" className="sm:min-w-40">
						Practice
					</TableHead>
					<TableHead scope="col" className="min-w-44">
						Run
					</TableHead>
					<TableHead scope="col" numeric className="text-right">
						Review calls
					</TableHead>
					<TableHead scope="col" numeric className="text-right">
						Time
					</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{practices.map(
					(practice) =>
						practice.script !== null && (
							<ScriptRow
								key={practice.practiceSlug}
								workspaceSlug={workspaceSlug}
								practice={practice}
								run={practice.script}
							/>
						),
				)}
			</TableBody>
		</Table>
	);
}

function ScriptRow({
	workspaceSlug,
	practice,
	run,
}: {
	workspaceSlug: string;
	practice: ReviewPractice;
	run: PracticeScriptRun;
}) {
	const fix = practiceScriptFix(run);
	const error = run.status === "FAILED" && hasText(run.error) ? run.error : undefined;
	return (
		<TableRow variant="static">
			<TableHead scope="row" variant="body">
				<InlineLink
					className="font-medium"
					render={<DetailStackLink entry={practiceLevel(practice.practiceSlug)} single />}
				>
					{practice.practiceName}
				</InlineLink>
			</TableHead>
			<TableCell className="align-top whitespace-normal">
				<span className="flex flex-col items-start gap-1">
					{definitionFor(
						PRACTICE_SCRIPT_RUN_STATUS_DEFS,
						run.status,
						UNKNOWN_SCRIPT_RUN_DEF,
					).result(run)}
					{fix !== undefined && (
						<PracticeScriptFixLink
							workspaceSlug={workspaceSlug}
							practiceSlug={practice.practiceSlug}
							practiceName={practice.practiceName}
							fix={fix}
						/>
					)}
					{error !== undefined && (
						<code className="max-w-sm font-mono text-xs wrap-anywhere whitespace-normal text-muted-foreground">
							{error}
						</code>
					)}
				</span>
			</TableCell>
			<TableCell numeric className="text-right align-top">
				<ReviewCalls calls={run.reviewModelCalls} />
			</TableCell>
			<TableCell numeric className="text-right align-top">
				{run.durationMs === null ? <Nothing /> : formatScriptTime(run.durationMs)}
			</TableCell>
		</TableRow>
	);
}

// The runner's own count of the practice's review-session calls; the proxy bills them for the whole review.
function ReviewCalls({ calls }: { calls: number | null }) {
	if (calls === null) {
		return <span className="text-muted-foreground">Not recorded</span>;
	}
	return calls === 0 ? <Nothing /> : formatNumber(calls);
}

function Nothing() {
	return (
		<>
			<span aria-hidden className="text-muted-foreground">
				—
			</span>
			<span className="sr-only">none</span>
		</>
	);
}
