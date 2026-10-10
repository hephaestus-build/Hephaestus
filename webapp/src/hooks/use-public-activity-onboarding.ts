import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	answerPublicActivityOnboardingMutation,
	getPublicActivityChoiceQueryKey,
	getPublicActivityOnboardingOptions,
	getPublicActivityOnboardingQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { PublicActivityAnswer } from "@/components/onboarding/PublicActivityOnboardingDialog";
import { problemDetailOf } from "@/lib/problem-detail";

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
	const path = { slug: workspaceSlug };
	const onboarding = useQuery({ ...getPublicActivityOnboardingOptions({ path }), enabled });
	// Only a failing save needs it, but a person who left the step is not asked again on this visit.
	const [deferred, setDeferred] = useState(false);
	const answer = useMutation({
		...answerPublicActivityOnboardingMutation(),
		onSuccess: (data, { body }) => {
			queryClient.setQueryData(getPublicActivityOnboardingQueryKey({ path }), data);
			// One answer for the account: User settings reads the same choice.
			void queryClient.invalidateQueries({ queryKey: getPublicActivityChoiceQueryKey({}) });
			toast.success(
				body.visible
					? "You show on public activity pages"
					: "You are hidden on public activity pages",
				{ description: "You can change this at any time in User settings." },
			);
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
		open: enabled && onboarding.data?.seen === false && !deferred,
		currentlyVisible: onboarding.data?.visible ?? true,
		answer: state,
		onAnswer: (visible: boolean) => answer.mutate({ path, body: { visible } }),
		onDefer: () => setDeferred(true),
	};
}
