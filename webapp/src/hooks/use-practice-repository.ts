import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
	changePracticeEditedInMutation,
	getPracticeOptions,
	getPracticeQueryKey,
	getPracticeRepositoryOptions,
	getPracticeRepositoryQueryKey,
	listPracticesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeFileProblem, PracticeRepository } from "@/api/types.gen";
import type {
	MoveToRepositoryControl,
	MoveToRepositoryRequest,
} from "@/components/admin/practices/WorkspaceMoveToRepositoryDialog";
import type { ConfirmAccessDialogProps } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { isRecord } from "@/lib/is-record";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";
import { toast } from "@/lib/toast";

/**
 * The practice repository of a workspace (ADR 0118), known once a read has answered: a later read
 * that fails keeps the last answer on screen. `null` means none.
 */
export function usePracticeRepositoryRead(workspaceSlug: string) {
	const query = useQuery(getPracticeRepositoryOptions({ path: { workspaceSlug } }));
	// A 204, typed `void`, reaches here as `null` at runtime: the client hands over its empty body that way.
	const { data } = query;
	let repository: PracticeRepository | null | undefined;
	if (data) {
		repository = data;
	} else if (data !== undefined) {
		repository = null;
	}
	return { query, repository };
}

/** Reloads what a write to the practice repository, or to where a practice is edited, changes. */
export function usePracticeRepositoryRefresh(workspaceSlug: string) {
	const queryClient = useQueryClient();
	// A changed or unset setting moves every practice edited in the old repository back here.
	const isWorkspacePractice = (queryKey: readonly unknown[]) => {
		if (queryOperationId(queryKey) !== "getPractice") {
			return false;
		}
		const [key] = queryKey;
		return isRecord(key) && isRecord(key.path) && key.path.workspaceSlug === workspaceSlug;
	};
	const refreshSetting = async () =>
		Promise.all([
			queryClient.invalidateQueries({
				queryKey: getPracticeRepositoryQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			}),
			queryClient.invalidateQueries({
				predicate: ({ queryKey }) => isWorkspacePractice(queryKey),
			}),
		]);
	const refreshPractice = async (practiceSlug: string) =>
		Promise.all([
			queryClient.invalidateQueries({
				queryKey: getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } }),
			}),
			queryClient.invalidateQueries({
				queryKey: listPracticesQueryKey({ path: { workspaceSlug } }),
			}),
		]);
	return { refreshSetting, refreshPractice };
}

/**
 * Confirm access for writes that ask for a recent sign-in: a stale session closes the write's dialog
 * and opens this one instead.
 */
export function useRecentSignIn() {
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	return {
		ask: setChallenge,
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

/** The one Move to repository this page may have open, in one drawer level. */
type OpenMove = { level: string; practiceSlug: string } & MoveToRepositoryRequest;

/**
 * Move to repository in each practice's level on Practice setup. The practice repository card has
 * its own hook, `usePracticeRepositorySetting`, which loads with the card.
 *
 * `openLevels` names the drawer levels on screen: a Move to repository ends with the level that
 * asked for it, so an answer that arrives later cannot reopen its dialog.
 */
export function useMoveToRepository(workspaceSlug: string, openLevels: readonly string[]) {
	const queryClient = useQueryClient();
	const [request, setRequest] = useState<OpenMove>();
	const recentSignIn = useRecentSignIn();
	const { repository } = usePracticeRepositoryRead(workspaceSlug);
	const { refreshSetting, refreshPractice } = usePracticeRepositoryRefresh(workspaceSlug);

	// Adjusted during render, not in an effect, so the level's next opening never shows the old one.
	if (request !== undefined && !openLevels.includes(request.level)) {
		setRequest(undefined);
	}

	// The move keeps its dialog open and busy until the page shows the result, then ends the
	// request: a dialog closed earlier would hand focus to a control that the reload replaces. Only a
	// pending request ends, so a level that closed meanwhile, which ended its own, stays closed.
	const endMove = (
		practiceSlug: string,
		refusal?: { reason: string; problems: PracticeFileProblem[] },
	) =>
		setRequest((current) => {
			if (current?.practiceSlug !== practiceSlug || current.phase !== "pending") {
				return current;
			}
			return refusal === undefined
				? undefined
				: { level: current.level, practiceSlug, phase: "refused", ...refusal };
		});

	const move = useMutation({
		...changePracticeEditedInMutation(),
		onSuccess: async (_data, variables) => {
			const { practiceSlug } = variables.path;
			await refreshPractice(practiceSlug);
			// A failed reload leaves a practice that still reads as editable here, so the dialog keeps
			// it as a refusal, and trying again reloads.
			const reload = queryClient.getQueryState(
				getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } }),
			);
			if (reload?.status === "error") {
				endMove(practiceSlug, {
					reason: problemDetailOf(
						reload.error,
						"The practice moved, but it did not reload. Try again to reload it.",
					),
					problems: [],
				});
				return;
			}
			// The reload is in the cache, so the render this schedules shows the practice read-only:
			// the dialog closes in the same render that shows Edit in Hephaestus, and focus finds it.
			endMove(practiceSlug);
			toast.success("This practice is now edited in the practice repository");
		},
		onError: async (error, variables) => {
			const { practiceSlug } = variables.path;
			const stepUp = stepUpChallengeOf(error);
			if (stepUp !== undefined) {
				endMove(practiceSlug);
				recentSignIn.ask(stepUp);
				return;
			}
			// A refusal can mean that the practice or the setting changed meanwhile: show them as they
			// now are. When the practice is edited in the repository already, the move has nothing
			// left to do, and the dialog closes onto Edit in Hephaestus.
			await refreshPractice(practiceSlug);
			await refreshSetting();
			const now = queryClient.getQueryData(
				getPracticeOptions({ path: { workspaceSlug, practiceSlug } }).queryKey,
			);
			if (now?.editedIn === "REPOSITORY") {
				endMove(practiceSlug);
				return;
			}
			endMove(practiceSlug, { reason: problemDetailOf(error), problems: error.problems ?? [] });
		},
	});

	// A dialog asks to close on Cancel, Escape or a press outside. While its request runs, it stays.
	const cancel = () => setRequest((open) => (open?.phase === "pending" ? open : undefined));

	return {
		/**
		 * Move to repository as one level runs it. `level` names the level, so the request ends when
		 * that level closes.
		 */
		move: (level: string, practiceSlug: string): MoveToRepositoryControl => ({
			repository,
			request: request?.level === level ? request : undefined,
			onOpen: () => setRequest({ level, practiceSlug, phase: "confirming" }),
			onCancel: cancel,
			onConfirm: () => {
				setRequest({ level, practiceSlug, phase: "pending" });
				move.mutate({ path: { workspaceSlug, practiceSlug }, body: { editedIn: "REPOSITORY" } });
			},
		}),
		confirmation: recentSignIn.confirmation,
	};
}
