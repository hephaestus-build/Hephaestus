import type { FeedbackDispute } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { RelativeTime } from "@/components/common/RelativeTime";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { DEVELOPER_RESPONSE_DEFS } from "@/components/practice-vocabulary/observation-dispute-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

import { feedbackLevel } from "./review-levels";

export interface DisputeAlertProps {
	dispute: FeedbackDispute;
	/** On an observation, the disputed feedback opens over it; on the feedback itself there is nothing to open. */
	linkFeedback?: boolean;
}

/**
 * A developer's standing dispute, in their own words. The explanation was written to the workspace's
 * admins; the feedback's own text stays as private as its place keeps it.
 */
export function DisputeAlert({ dispute, linkFeedback = false }: DisputeAlertProps) {
	const Icon = DEVELOPER_RESPONSE_DEFS.DISPUTED.icon;
	const place = DELIVERY_PLACE_DEFS[dispute.channel].label;
	return (
		<Alert variant="warning">
			<Icon />
			<AlertTitle>The developer disputes this</AlertTitle>
			<AlertDescription>
				<p>
					{linkFeedback ? (
						<InlineLink
							className="font-medium"
							render={<DetailStackLink entry={feedbackLevel(dispute.feedbackId)} />}
						>
							Feedback {place.toLowerCase()}
						</InlineLink>
					) : (
						"This feedback"
					)}{" "}
					was disputed <RelativeTime value={dispute.disputedAt} />:
				</p>
				<blockquote className="border-l-2 pl-3 break-words whitespace-pre-wrap text-foreground">
					{dispute.explanation}
				</blockquote>
				<p>
					Mark the observation incorrect if it was wrong, or withdraw practice-page feedback whose
					words were wrong. While the dispute stands, a later review of the same work does not raise
					the same point again.
				</p>
			</AlertDescription>
		</Alert>
	);
}
