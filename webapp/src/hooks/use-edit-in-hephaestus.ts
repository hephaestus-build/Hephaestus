import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "@/lib/toast";

import {
	changePracticeEditedInMutation,
	getPracticeOptions,
	getPracticeQueryKey,
	getPracticeRepositoryOptions,
	listPracticesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { EditInHephaestusControl } from "@/components/admin/practices/WorkspaceEditInHephaestusDialog";
import type { ConfirmAccessDialogProps } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import {
	problemDetailOf,
	problemStatusOf,
	type StepUpChallenge,
	stepUpChallengeOf,
} from "@/lib/problem-detail";

/** Where one Edit in Hephaestus stands: asked, sent, or refused. Absent once it ends. */
type EditInHephaestusRequest = { level: string; practiceSlug: string } & (
	| { phase: "confirming" }
	| { phase: "pending" }
	| { phase: "refused"; refusal: string }
);

/**
 * Edit in Hephaestus for a practice edited in the practice repository (ADR 0118), with the name of
 * that repository for the read-only notice and the confirmation. `enabled` reads the repository only
 * where a practice edited there is on screen, so a workspace without one sends no request.
 */
export function useEditInHephaestus(workspaceSlug: string, enabled: boolean) {
	const queryClient = useQueryClient();
	// The server answers 204 with no body when the workspace has no practice repository, which the
	// client hands over as `null`, so the name is absent in that case too.
	const repository = useQuery({
		...getPracticeRepositoryOptions({ path: { workspaceSlug } }),
		enabled,
	});
	// Where a practice is edited decides what reviews judge, so the server asks for a recent sign-in.
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const refresh = async (practiceSlug: string) =>
		Promise.all([
			queryClient.invalidateQueries({
				queryKey: getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			}),
		]);
	// The one Edit in Hephaestus the page runs, and the only home of its dialog's open state. Keyed by
	// level, because a stack can hold a practice's panel and its editor.
	const [request, setRequest] = useState<EditInHephaestusRequest>();
	const change = useMutation({
		...changePracticeEditedInMutation(),
		onSuccess: async (_data, variables) => {
			const { practiceSlug } = variables.path;
			await refresh(practiceSlug);
			// A failed reload would leave a practice that still reads as read-only. Thrown here, it ends
			// as a refusal the dialog shows, and trying again reloads.
			const reload = queryClient.getQueryState(
				getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } }),
			);
			if (reload?.status === "error") {
				throw reload.error ?? new Error("The practice did not reload.");
			}
			// The reload is in the cache, so the render this schedules already shows the practice as
			// editable: the dialog closes in the same render that shows the control which edits it, and
			// its final focus finds that control.
			setRequest((current) => (current?.practiceSlug === practiceSlug ? undefined : current));
			toast.success("You can edit this practice in Hephaestus again");
		},
		onError: async (error, variables) => {
			const { practiceSlug } = variables.path;
			const stepUp = stepUpChallengeOf(error);
			if (stepUp !== undefined) {
				setRequest(undefined);
				setChallenge(stepUp);
				return;
			}
			// A conflict means where the practice is edited changed meanwhile: show it as it now is. When
			// it is editable now, the request has nothing left to do, and the dialog closes onto its editor.
			if (problemStatusOf(error) === 409) {
				await refresh(practiceSlug);
				const now = queryClient.getQueryData(
					getPracticeOptions({ path: { workspaceSlug, practiceSlug } }).queryKey,
				);
				if (now?.editedIn === "HEPHAESTUS") {
					setRequest((current) => (current?.practiceSlug === practiceSlug ? undefined : current));
					return;
				}
			}
			setRequest((current) =>
				current?.practiceSlug === practiceSlug && current.phase === "pending"
					? { ...current, phase: "refused", refusal: problemDetailOf(error) }
					: current,
			);
		},
	});

	return {
		/**
		 * Edit in Hephaestus as one level runs it. `level` names the level, so a practice open in two
		 * levels confirms in one of them.
		 */
		control: (level: string, practiceSlug: string): EditInHephaestusControl => {
			const own = request?.level === level ? request : undefined;
			return {
				repositoryName: repository.data?.nameWithOwner,
				confirming: own !== undefined,
				pending: own?.phase === "pending",
				error: own?.phase === "refused" ? own.refusal : undefined,
				onConfirmingChange: (open) => {
					if (open) {
						setRequest({ level, practiceSlug, phase: "confirming" });
					} else if (own?.phase !== "pending") {
						setRequest(undefined);
					}
				},
				onConfirm: () => {
					setRequest({ level, practiceSlug, phase: "pending" });
					change.mutate({
						path: { workspaceSlug, practiceSlug },
						body: { editedIn: "HEPHAESTUS" },
					});
				},
			};
		},
		confirmation: {
			open: challenge !== undefined,
			onOpenChange: (open) => {
				if (!open) {
					setChallenge(undefined);
				}
			},
			maxAgeSeconds: challenge?.maxAgeSeconds,
			providers: confirmAccess.providers,
			loading: confirmAccess.loading,
			error: confirmAccess.error,
			onRetry: confirmAccess.retry,
			onSignIn: confirmAccess.signIn,
		} satisfies ConfirmAccessDialogProps,
	};
}
