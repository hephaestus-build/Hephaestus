import { Link } from "@tanstack/react-router";
import { PencilIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewPracticeCounts } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { RESULT_MARK_DEFS } from "@/components/practice-vocabulary/result-mark-defs";
import { Skeleton } from "@/components/ui/skeleton";

import { feedbackComposed } from "./practice-totals";
import { familyCount, resultSlots, slotsTotal } from "./review-outcomes";

export interface PracticeResultsSummaryProps {
	workspaceSlug: string;
	practiceSlug: string;
	practiceName: string | undefined;
	rangeLabel: string;
	counts: PanelState<{ counts?: ReviewPracticeCounts; stale: boolean }>;
}

export function PracticeResultsSummary({
	workspaceSlug,
	practiceSlug,
	practiceName,
	rangeLabel,
	counts: state,
}: PracticeResultsSummaryProps) {
	const counts = state.status === "ready" ? state.counts : undefined;
	const name = practiceName ?? counts?.practiceName ?? "This practice";
	return (
		<section
			aria-label={`Counts for ${name}`}
			className="flex flex-col gap-3 rounded-lg border p-4 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between"
		>
			<div className="min-w-0 space-y-1">
				<h2 className="flex min-w-0 items-center gap-2 text-base font-semibold break-words">
					{counts?.group && (
						<GroupPill
							size="sm"
							slug={counts.group.slug}
							name={counts.group.name}
							icon={counts.group.icon}
							color={counts.group.color}
							srLabel
						/>
					)}
					{name}
				</h2>
				<p className="text-xs text-muted-foreground">{rangeLabel}</p>
				<p className="text-sm">
					<InlineLink
						className="font-medium"
						render={
							<Link
								to="/w/$workspaceSlug/admin/practices"
								params={{ workspaceSlug }}
								search={detailSearch(practiceFormLevel(practiceSlug))}
							/>
						}
					>
						<PencilIcon aria-hidden className="mr-1 inline size-3.5" />
						Edit in Practice setup
					</InlineLink>
				</p>
			</div>
			<Figures state={state} />
		</section>
	);
}

function Figures({ state }: { state: PracticeResultsSummaryProps["counts"] }) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the counts for this practice"
				onRetry={state.onRetry}
			/>
		);
	}
	// Cached figures from another date range cannot describe this range.
	if (state.status === "loading" || (state.stale && state.counts === undefined)) {
		return (
			<div aria-hidden className="flex gap-6">
				{Array.from({ length: 3 }, (_, index) => (
					<Skeleton key={index} className="h-10 w-24" />
				))}
			</div>
		);
	}
	if (state.counts === undefined) {
		return (
			<p className="max-w-sm text-sm text-muted-foreground">
				No review recorded an observation about this practice in these days.
			</p>
		);
	}
	const { results, resultsMarkedIncorrect, feedback } = state.counts;
	const slots = resultSlots(results);
	const total = slotsTotal(slots);
	const feedbackTotal = feedbackComposed(feedback);
	return (
		<dl
			aria-busy={state.stale || undefined}
			className={cn(
				"grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:flex sm:flex-wrap",
				state.stale && STALE,
			)}
		>
			<Figure label="Observations" value={String(total)}>
				{slots
					.filter((slot) => slot.count > 0)
					.map((slot) => `${slot.count} ${slot.label}`)
					.join(" · ")}
			</Figure>
			<Figure
				label={RESULT_MARK_DEFS.INCORRECT.label}
				value={`${resultsMarkedIncorrect} of ${total}`}
			/>
			<Figure
				label="Feedback delivered"
				value={
					feedbackTotal === 0 ? "None" : `${familyCount(feedback, "DELIVERED")} of ${feedbackTotal}`
				}
			/>
		</dl>
	);
}

function Figure({ label, value, children }: { label: string; value: string; children?: string }) {
	return (
		<div className="min-w-0">
			<dt className="text-xs text-muted-foreground">{label}</dt>
			<dd className="font-medium tabular-nums">{value}</dd>
			{children !== undefined && children !== "" && (
				<dd className="text-xs text-muted-foreground">{children}</dd>
			)}
		</div>
	);
}
