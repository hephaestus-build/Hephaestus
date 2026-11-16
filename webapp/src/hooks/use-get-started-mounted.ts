import { useState } from "react";

import {
	type GetStartedOnboarding,
	getStartedApplies,
	type GetStartedReview,
} from "@/components/onboarding/get-started-open";

/**
 * Whether a route keeps the reader's Get started list mounted. The list applies while a setup step is
 * open, never in a user view. Once mounted it stays, so the step that closes it is still announced.
 */
export function useGetStartedMounted({
	userView,
	onboarding,
	review,
}: {
	userView: boolean;
	onboarding: GetStartedOnboarding | undefined;
	review: GetStartedReview | undefined;
}): boolean {
	const applies = !userView && getStartedApplies(onboarding, review);
	const [latched, setLatched] = useState(applies);
	if (applies && !latched) {
		setLatched(true);
	}
	return latched || applies;
}
