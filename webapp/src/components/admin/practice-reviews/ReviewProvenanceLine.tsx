import { InlineLink } from "@/components/common/InlineLink";
import { RelativeTime } from "@/components/common/RelativeTime";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";

import { reviewLevel } from "./review-levels";

export interface ReviewProvenanceLineProps {
	agentJobId: string;
	/** How this record came about: "Observed", "Composed". A verb, so the line reads as a sentence. */
	verb: string;
	at: Date;
}

/** Answers "which review made this?" with a link that opens it, rather than the job's UUID. */
export function ReviewProvenanceLine({ agentJobId, verb, at }: ReviewProvenanceLineProps) {
	return (
		<p>
			{verb}{" "}
			<InlineLink render={<DetailStackLink entry={reviewLevel(agentJobId)} />}>
				in a review
			</InlineLink>{" "}
			<RelativeTime value={at} />
		</p>
	);
}
