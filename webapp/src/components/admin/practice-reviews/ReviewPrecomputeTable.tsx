import type { ReviewPrecompute, ReviewPrecomputeModel } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { PrecomputeFixLink } from "@/components/practice-trace/PrecomputeFixLink";
import { DataHandlingMark } from "@/components/practice-vocabulary/DataHandlingMark";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { precomputeNotRated } from "@/components/practice-vocabulary/precompute-not-rated-defs";
import {
	PRECOMPUTE_RUN_STATUS_DEFS,
	precomputeFix,
} from "@/components/practice-vocabulary/precompute-run-status-defs";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { hasText } from "@/lib/text";

import { practiceLevel } from "./review-levels";

const count = new Intl.NumberFormat("en-GB");
const seconds = new Intl.NumberFormat("en-GB", {
	minimumFractionDigits: 1,
	maximumFractionDigits: 1,
});

/**
 * A script's run time as `4.2 s`, or `2 min 5 s` from a minute on. A run under 0.1 s does not read
 * as `0.0 s`, as if it did not run.
 */
function formatScriptTime(durationMs: number): string {
	if (durationMs < 100) {
		return "< 0.1 s";
	}
	const tenths = Math.round(durationMs / 100);
	if (tenths < 600) {
		return `${seconds.format(tenths / 10)} s`;
	}
	const total = Math.round(durationMs / 1000);
	return `${count.format(Math.floor(total / 60))} min ${total % 60} s`;
}

export interface ReviewPrecomputeTableProps {
	workspaceSlug: string;
	/** One entry per script the review's latest attempt staged. */
	scripts: ReviewPrecompute[];
}

/**
 * What each practice's precompute script did before one review: how it ended, which models it had,
 * what they could not rate and why, how many calls the proxy counted, and how long it ran. The calls
 * to the review's own model are the review's, and spend is on AI usage, so neither is here.
 */
export function ReviewPrecomputeTable({ workspaceSlug, scripts }: ReviewPrecomputeTableProps) {
	// On a narrow screen, the practice and its result fit the screen's width and wrap there. The
	// figures to their right scroll into view.
	return (
		<Table bordered className="sm:min-w-160">
			<caption className="sr-only">What each practice’s script did before this review</caption>
			<TableHeader>
				<TableRow variant="static">
					<TableHead scope="col" className="sm:min-w-40">
						Practice
					</TableHead>
					<TableHead scope="col" className="min-w-44">
						Result
					</TableHead>
					<TableHead scope="col">Models</TableHead>
					<TableHead scope="col" numeric className="text-right">
						Not rated
					</TableHead>
					<TableHead scope="col" numeric className="text-right">
						Calls
					</TableHead>
					<TableHead scope="col" numeric className="text-right">
						Time
					</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{scripts.map((script) => (
					<ScriptRow key={script.practiceSlug} workspaceSlug={workspaceSlug} script={script} />
				))}
			</TableBody>
		</Table>
	);
}

/**
 * One script. The result carries its fix and, for a failed script, its error, so the way out and
 * what went wrong sit together and wrap inside the result's own column.
 */
function ScriptRow({ workspaceSlug, script }: { workspaceSlug: string; script: ReviewPrecompute }) {
	const { run } = script;
	const skipped = run.status === "SKIPPED";
	const fix = precomputeFix(run);
	// A practice deleted since the review has no script left to edit.
	const shownFix = fix?.kind === "EDIT" && !hasText(script.practiceName) ? undefined : fix;
	const notRated = skipped ? undefined : precomputeNotRated(run.models);
	const error = run.status === "FAILED" && hasText(script.error) ? script.error : undefined;
	const calls = script.models.reduce((sum, model) => sum + model.calls, 0);
	return (
		<TableRow variant="static">
			<TableHead scope="row" variant="body">
				<PracticeName script={script} />
			</TableHead>
			<TableCell className="align-top whitespace-normal">
				<span className="flex flex-col items-start gap-1">
					{PRECOMPUTE_RUN_STATUS_DEFS[run.status].result(run)}
					{shownFix !== undefined && (
						<PrecomputeFixLink
							workspaceSlug={workspaceSlug}
							practiceSlug={script.practiceSlug}
							practiceName={script.practiceName ?? script.practiceSlug}
							fix={shownFix}
						/>
					)}
					{error !== undefined && (
						<code className="max-w-sm font-mono text-xs wrap-anywhere whitespace-normal text-muted-foreground">
							{error}
						</code>
					)}
				</span>
			</TableCell>
			<TableCell className="align-top">
				<ScriptModels models={script.models} />
			</TableCell>
			<TableCell numeric className="text-right align-top">
				{notRated === undefined || notRated.count === 0 ? (
					<Nothing />
				) : (
					<>
						{count.format(notRated.count)}
						<ul className="text-xs text-muted-foreground">
							{notRated.reasons.map((reason) => (
								<li key={reason}>{reason}</li>
							))}
						</ul>
					</>
				)}
			</TableCell>
			<TableCell numeric className="text-right align-top">
				{skipped || script.models.length === 0 ? <Nothing /> : count.format(calls)}
			</TableCell>
			<TableCell numeric className="text-right align-top">
				{script.durationMs === undefined ? <Nothing /> : formatScriptTime(script.durationMs)}
			</TableCell>
		</TableRow>
	);
}

function PracticeName({ script }: { script: ReviewPrecompute }) {
	if (!hasText(script.practiceName)) {
		return (
			<>
				<div className="font-mono text-xs">{script.practiceSlug}</div>
				<div className="text-xs text-muted-foreground">Not a current practice</div>
			</>
		);
	}
	return (
		<InlineLink
			className="font-medium"
			render={<DetailStackLink entry={practiceLevel(script.practiceSlug)} />}
		>
			{script.practiceName}
		</InlineLink>
	);
}

/**
 * Each model with the tier of the model the review had for it. The tier's icon is enough beside the
 * kind's name, and a screen reader hears its label. A model with counted calls ran even when its
 * tier is unknown, as for a script that did not finish, so it claims nothing about its assignment.
 */
function ScriptModels({ models }: { models: ReviewPrecomputeModel[] }) {
	if (models.length === 0) {
		return <Nothing />;
	}
	return (
		<ul className="flex flex-col gap-1">
			{models.map((model) => (
				<li key={model.purpose} className="flex items-center gap-2">
					<ModelKindMark purpose={model.purpose} />
					{model.tier === undefined ? (
						model.calls === 0 && <span className="text-muted-foreground">No model</span>
					) : (
						<DataHandlingMark tier={model.tier} />
					)}
				</li>
			))}
		</ul>
	);
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
