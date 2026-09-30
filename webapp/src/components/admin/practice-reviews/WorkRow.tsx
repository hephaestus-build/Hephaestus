import type { TracedArtifact } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { signalCountsLabel } from "@/components/practice-trace/trace-format";
import {
	TRACED_WORK_DEFS,
	tracedWorkState,
} from "@/components/practice-vocabulary/traced-work-defs";
import { reviewedWorkName } from "@/lib/artifact-kinds";
import { hasText } from "@/lib/text";

import { workLevel } from "./review-levels";
import { ReviewRow, ReviewRowLink, ReviewRowMeta } from "./ReviewRow";

export interface WorkRowProps {
	work: TracedArtifact;
}

/**
 * One piece of work Hephaestus recorded, and how much of what happened to it started a review. The leading
 * icon says whether anything did; the row opens the work's level, where the timeline says why not.
 * The work is named the way its provider writes it — "Pull request #1423", "Merge request !1423".
 * A kind this build cannot name in a URL has no level, so its title is not a link.
 */
export function WorkRow({ work }: WorkRowProps) {
	const entry = workLevel(work.artifactKind, work.artifactId);
	const WorkIcon = reviewedWorkIcon(work.reviewedWork.kind, work.reviewedWork.provider);
	const title = work.reviewedWork.title ?? work.reviewedWork.label;
	return (
		<ReviewRow
			status={TRACED_WORK_DEFS[tracedWorkState(work)]}
			title={entry ? <ReviewRowLink entry={entry}>{title}</ReviewRowLink> : title}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<span key="work" className="inline-flex items-center gap-1.5">
								<WorkIcon className="size-3.5 shrink-0" aria-hidden />
								{reviewedWorkName(work.reviewedWork)}
							</span>,
							hasText(work.reviewedWork.container) && (
								<span key="container" className="min-w-0 break-words">
									{work.reviewedWork.container}
								</span>
							),
							// See `ObservationRow`: no hover target under a stretched row link.
							<RelativeTime key="last" value={work.lastSignalAt} tooltip={false} />,
						]}
					/>
					<p>{signalCountsLabel(work.signalCount, work.reviewedSignalCount)}</p>
				</>
			}
		/>
	);
}
