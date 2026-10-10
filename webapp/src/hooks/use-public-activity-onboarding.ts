import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useState } from "react";

import {
	answerPublicActivityOnboardingMutation,
	getPublicActivityChoiceQueryKey,
	getPublicActivityOnboardingOptions,
	getPublicActivityOnboardingQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { PublicActivityAnswer } from "@/components/onboarding/PublicActivityOnboardingDialog";
import { problemDetailOf } from "@/lib/problem-detail";
import { announcePublicActivityChoice } from "@/lib/public-activity-choice";
import { refreshPublicActivity } from "@/runtime/tanstack-query/refresh-public-activity";

/** A save that has not answered by now has failed, so the step never waits on it forever. */
const SAVE_TIMEOUT_MS = 15_000;

/**
 * A signed-in person's step for a workspace that publishes its activity: whether they have answered,
 * and the answer. `enabled` is off where the workspace does not publish, or where the reader is not
 * the account itself, such as a user view; the server answers 404 there.
 */
export function usePublicActivityOnboarding({
	workspaceSlug,
	enabled,
}: {
	workspaceSlug: string;
	enabled: boolean;
}) {
	const queryClient = useQueryClient();
	const router = useRouter();
	const path = { slug: workspaceSlug };
	const onboarding = useQuery({ ...getPublicActivityOnboardingOptions({ path }), enabled });
	// Leaving the step is for this visit only: the next load asks again.
	const [deferredFor, setDeferredFor] = useState<string>();
	const answer = useMutation({
		...answerPublicActivityOnboardingMutation(),
		// Offline, a paused save would hold both answers forever; a failed one lets the person go on.
		networkMode: "always",
		onSuccess: async (data, { body }) => {
			queryClient.setQueryData(getPublicActivityOnboardingQueryKey({ path }), data);
			// One answer for the account: User settings reads the same choice.
			void queryClient.invalidateQueries({ queryKey: getPublicActivityChoiceQueryKey({}) });
			announcePublicActivityChoice(body.visible);
			// A public page open now still lists this person until it reads again.
			await refreshPublicActivity(queryClient);
			await router.invalidate();
		},
	});

	let state: PublicActivityAnswer = { status: "idle" };
	if (answer.isPending) {
		state = { status: "saving", visible: answer.variables.body.visible };
	} else if (answer.isError) {
		state = {
			status: "error",
			message: problemDetailOf(answer.error, "We could not save your choice. Try again."),
		};
	}
	return {
		open: enabled && onboarding.data?.seen === false && deferredFor !== workspaceSlug,
		currentlyVisible: onboarding.data?.visible ?? true,
		answer: state,
		onAnswer: (visible: boolean) =>
			answer.mutate({ path, body: { visible }, signal: AbortSignal.timeout(SAVE_TIMEOUT_MS) }),
		onDefer: () => setDeferredFor(workspaceSlug),
	};
}
