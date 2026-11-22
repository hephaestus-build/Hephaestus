import { Link } from "@tanstack/react-router";
import { ArrowRightIcon, ScanSearchIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { PracticeEditedIn, PracticeScriptRun } from "@/api/types.gen";
import { reviewLevel } from "@/components/admin/practice-reviews/review-levels";
import { InlineLink } from "@/components/common/InlineLink";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import {
	practiceScriptFix,
	practiceScriptRunSentence,
} from "@/components/practice-vocabulary/practice-script-run-status-defs";

import { PracticeScriptFixLink } from "./PracticeScriptFixLink";

export interface PracticeScriptTraceLineProps {
	workspaceSlug: string;
	practiceSlug: string;
	practiceName: string;
	/** Where the practice is edited now, which decides where its script's fix link leads. */
	editedIn: PracticeEditedIn;
	reviewId: string;
	run: PracticeScriptRun;
	/**
	 * How *Details* opens the review, where the line sits in a stack that a review opens over. The
	 * label carries a trailing icon, so the link lays it out inline. Absent, it opens the review on
	 * Practice reviews.
	 */
	reviewLink?: (reviewId: string, label: ReactNode) => ReactNode;
	/** `false` where the line sits on the review's own sheet, beside its section Practice scripts. */
	details?: boolean;
}

export function PracticeScriptTraceLine({
	workspaceSlug,
	practiceSlug,
	practiceName,
	editedIn,
	reviewId,
	run,
	reviewLink,
	details: showDetails = true,
}: PracticeScriptTraceLineProps) {
	const fix = practiceScriptFix(run);
	const details = (
		<>
			Details <span className="sr-only">of the practice script for {practiceName}</span>
			<ArrowRightIcon className="size-3 shrink-0" aria-hidden />
		</>
	);
	return (
		<span className="flex min-w-0 items-start gap-1.5 text-pretty">
			<ScanSearchIcon className="mt-px size-3.5 shrink-0" aria-hidden />
			<span className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
				<span>{practiceScriptRunSentence(run)}</span>
				{fix !== undefined && (
					<PracticeScriptFixLink
						workspaceSlug={workspaceSlug}
						practiceSlug={practiceSlug}
						practiceName={practiceName}
						editedIn={editedIn}
						fix={fix}
					/>
				)}
				{showDetails &&
					(reviewLink?.(reviewId, details) ?? (
						<InlineLink
							render={
								<Link
									to="/w/$workspaceSlug/admin/practices/reviews"
									params={{ workspaceSlug }}
									search={detailSearch(reviewLevel(reviewId))}
								/>
							}
							className="inline-flex items-center gap-1 font-medium"
						>
							{details}
						</InlineLink>
					))}
			</span>
		</span>
	);
}
