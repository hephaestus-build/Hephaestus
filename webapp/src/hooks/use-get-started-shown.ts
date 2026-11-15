import { useState } from "react";

import {
	type GetStartedOnboarding,
	getStartedApplies,
	type GetStartedReview,
} from "@/components/onboarding/get-started-open";

/**
 * Whether a route shows the reader's Get started list, and whether it keeps it mounted. The list
 * shows while a setup step is open, never in a user view. Once shown it stays mounted, so the step
 * that closes it is still announced.
 */
export function useGetStartedShown({
	userView,
	onboarding,
	review,
}: {
	userView: boolean;
	onboarding: GetStartedOnboarding | undefined;
	review: GetStartedReview | undefined;
}): { shown: boolean; mounted: boolean } {
	const shown = !userView && getStartedApplies(onboarding, review);
	const [latched, setLatched] = useState(shown);
	if (shown && !latched) {
		setLatched(true);
	}
	return { shown, mounted: latched || shown };
}
