import type { TracedArtifact } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { signalCountsLabel } from "@/components/practice-trace/trace-format";
import {
	TRACED_WORK_DEFS,
	tracedWorkState,
} from "@/components/practice-vocabulary/traced-work-defs";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { hasText } from "@/lib/text";

import { workLevel } from "./review-levels";
import { ReviewRow, ReviewRowLink, ReviewRowMeta } from "./ReviewRow";

export interface WorkRowProps {
	work: TracedArtifact;
}

/**
 * One piece of work Hephaestus recorded, and how much of what happened to it started a review. The leading
 * icon says whether anything did; the row opens the work's level, where the timeline says why not.
 * A kind this build cannot name in a URL has no level, so its title is not a link.
 */
export function WorkRow({ work }: WorkRowProps) {
	const entry = workLevel(work.artifactKind, work.artifactId);
	return (
		<ReviewRow
			status={TRACED_WORK_DEFS[tracedWorkState(work)]}
			title={entry ? <ReviewRowLink entry={entry}>{work.title}</ReviewRowLink> : work.title}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<WorkTypeLabel key="kind" artifactKind={work.artifactKind} />,
							hasText(work.container) && (
								<span key="container" className="min-w-0 break-words">
									{work.container}
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
