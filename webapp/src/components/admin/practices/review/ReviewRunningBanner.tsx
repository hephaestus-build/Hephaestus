import type { BadgeVariant } from "@/components/practice-vocabulary/status-def";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

import {
	REVIEW_RUNNING_DEFS,
	type ReviewRunningState,
	reviewRunningDescription,
	reviewRunningTone,
} from "./review-readiness";

/**
 * The registry speaks in badge tones because that is the shared vocabulary of status across the
 * practice surfaces; the Alert kit names four of its own. Kept as a total map so a tone added to the
 * registry fails `typecheck:webapp` here rather than falling back to a silent neutral banner.
 */
const ALERT_VARIANTS: Record<BadgeVariant, "default" | "destructive" | "success" | "warning"> = {
	default: "default",
	secondary: "default",
	outline: "default",
	destructive: "destructive",
	success: "success",
	warning: "warning",
};

export interface ReviewRunningBannerProps {
	running: ReviewRunningState;
}

/**
 * The one thing worth saying above three tabs of settings: whether any of it is in force. It is a
 * status, so it is drawn as one — icon, headline, tone — rather than a grey sentence that reads as
 * boilerplate. Healthy is affirmed once, quietly green; only the states that stop reviews escalate to
 * warning, which is what keeps a tinted header meaning something when it appears. The icon and the
 * headline repeat what the tone says, so colour is never the only carrier (WCAG 2.2 SC 1.4.1).
 *
 * <p>This is the page's only answer to "are reviews running" and the only place model readiness is
 * stated: the settings below say how to change the model, never whether it is ready, so the two
 * cannot drift into contradicting each other.
 *
 * <p>`role="status"`, not `alert`: this is the standing state of the page rather than a response to
 * anything the reader just did, and an assertive announcement on every visit would interrupt them
 * mid-sentence.
 */
export function ReviewRunningBanner({ running }: ReviewRunningBannerProps) {
	const { label, icon: ToneIcon, badgeVariant } = REVIEW_RUNNING_DEFS[reviewRunningTone(running)];

	return (
		<Alert variant={ALERT_VARIANTS[badgeVariant]} role="status">
			<ToneIcon />
			<AlertTitle>{label}</AlertTitle>
			<AlertDescription>{reviewRunningDescription(running)}</AlertDescription>
		</Alert>
	);
}
