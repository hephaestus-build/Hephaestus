import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
	changePracticeEditedInMutation,
	getPracticeOptions,
	getPracticeQueryKey,
	getPracticeRepositoryOptions,
	getPracticeRepositoryQueryKey,
	listPracticesQueryKey,
	listReviewItemsInfiniteOptions,
	setPracticeRepositoryMutation,
	unsetPracticeRepositoryMutation,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeRepository, SetPracticeRepositoryRequest } from "@/api/types.gen";
import type {
	MoveToRepositoryControl,
	MoveToRepositoryRequest,
} from "@/components/admin/practices/WorkspaceMoveToRepositoryDialog";
import type {
	MonitoredRepositoryOptions,
	PracticeRepositoryAction,
	PracticeRepositoryCardState,
	PracticeRepositoryRequest,
	PracticeRepositorySettingControl,
} from "@/components/admin/practices/WorkspacePracticeRepositoryCard";
import type { ConfirmAccessDialogProps } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { isRecord } from "@/lib/is-record";
import { ITEM_KIND } from "@/lib/item-kinds";
import {
	directoryErrorOf,
	isPracticeRepository,
	practiceFolderProblemsOf,
} from "@/lib/practice-repository";
import {
	problemDetailOf,
	type StepUpChallenge,
	stepUpChallengeOf,
} from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";
import { toast } from "@/lib/toast";
import { loadedPages, offsetPageParams } from "@/runtime/tanstack-query/list-pages";
import { useEveryPage } from "@/runtime/tanstack-query/use-every-page";

const PICKER_PAGE_SIZE = 100;

/**
 * The one dialog this page may have open for the practice repository: a setting action, or Move
 * to repository in one drawer level. It lives here and nowhere else, so a dialog opens only because
 * the reader asked, and nothing that closes it can leave a request behind to reopen it.
 */
type OpenRequest =
	| ({ kind: "setting" } & PracticeRepositoryRequest)
	| ({ kind: "move"; level: string; practiceSlug: string } & MoveToRepositoryRequest);

const SAVED: Record<PracticeRepositoryAction, string> = {
	set: "Practice repository set",
	change: "Practice repository changed",
	unset: "Practice repository unset",
};

/**
 * The practice repository of a workspace (ADR 0118) on Practice setup: its card, which sets,
 * changes and unsets it, and Move to repository in each practice's level. Every write asks for a
 * recent sign-in, so a stale session closes the dialog and opens Confirm access instead.
 *
 * `openLevels` names the drawer levels on screen: a Move to repository ends with the level that
 * asked for it, so an answer that arrives later cannot reopen its dialog.
 */
export function usePracticeRepository(workspaceSlug: string, openLevels: readonly string[]) {
	const queryClient = useQueryClient();
	const [request, setRequest] = useState<OpenRequest>();
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	// Adjusted during render, not in an effect, so the level's next opening never shows the old one.
	if (request?.kind === "move" && !openLevels.includes(request.level)) {
		setRequest(undefined);
	}

	const repositoryQuery = useQuery(getPracticeRepositoryOptions({ path: { workspaceSlug } }));
	// Known once a read has answered: a later read that fails keeps the last answer on screen.
	const { data } = repositoryQuery;
	let repository: PracticeRepository | null | undefined;
	if (data !== undefined) {
		repository = isPracticeRepository(data) ? data : null;
	}

	const choosing = request?.kind === "setting" && request.action !== "unset";
	const repositoriesQuery = useInfiniteQuery({
		...listReviewItemsInfiniteOptions({
			path: { workspaceSlug },
			query: { itemKind: ITEM_KIND.repository, size: PICKER_PAGE_SIZE },
		}),
		...offsetPageParams,
		enabled: choosing,
	});
	// One repository is chosen from all of them, so the list reads to its end.
	useEveryPage(repositoriesQuery);

	const set = useMutation(setPracticeRepositoryMutation());
	const unset = useMutation(unsetPracticeRepositoryMutation());
	const move = useMutation(changePracticeEditedInMutation());

	const isWorkspacePractice = (queryKey: readonly unknown[]) => {
		if (queryOperationId(queryKey) !== "getPractice") {
			return false;
		}
		const [key] = queryKey;
		return isRecord(key) && isRecord(key.path) && key.path.workspaceSlug === workspaceSlug;
	};
	// A changed or unset setting moves every practice edited in the old repository back here.
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

	/**
	 * Ends `from` with `next`, unless the request changed meanwhile: a level that closed while its
	 * move ran has already ended it.
	 */
	const settle = (from: OpenRequest, next: OpenRequest | undefined) =>
		setRequest((current) => (current === from ? next : current));

	/** Ends the request and asks for a recent sign-in, when that is what the refusal asks for. */
	const askedToConfirmAccess = (from: OpenRequest, error: unknown) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp === undefined) {
			return false;
		}
		settle(from, undefined);
		setChallenge(stepUp);
		return true;
	};

	// Each write keeps its dialog open and busy until the page shows the result, then closes it: a
	// dialog closed earlier would hand focus to a control that the reload is about to replace.
	const changeSetting = async (
		action: PracticeRepositoryAction,
		send: () => Promise<PracticeRepository | null>,
	) => {
		const pending: OpenRequest = { kind: "setting", action, phase: "pending" };
		setRequest(pending);
		let saved: PracticeRepository | null;
		try {
			saved = await send();
		} catch (error) {
			if (askedToConfirmAccess(pending, error)) {
				return;
			}
			// A refusal can mean the setting changed meanwhile, such as an Unset that another admin got
			// to first: show it as it now is, beside the reason.
			await refreshSetting();
			settle(pending, {
				kind: "setting",
				action,
				phase: "refused",
				reason: problemDetailOf(error),
				directoryError: directoryErrorOf(error),
			});
			return;
		}
		// The answer is the setting now, so the card shows it even if the read after it fails. The read
		// of "none" is `null`, as the client hands over a 204.
		queryClient.setQueryData<PracticeRepository | null>(
			getPracticeRepositoryQueryKey({ path: { workspaceSlug } }),
			saved,
		);
		await refreshSetting();
		settle(pending, undefined);
		toast.success(SAVED[action]);
	};

	const moveToRepository = async (level: string, practiceSlug: string) => {
		const pending: OpenRequest = { kind: "move", level, practiceSlug, phase: "pending" };
		setRequest(pending);
		try {
			await move.mutateAsync({
				path: { workspaceSlug, practiceSlug },
				body: { editedIn: "REPOSITORY" },
			});
		} catch (error) {
			if (askedToConfirmAccess(pending, error)) {
				return;
			}
			// A refusal can mean that the practice or the setting changed meanwhile: show them as they
			// now are. When the practice is edited in the repository already, the move has nothing left
			// to do, and the dialog closes onto Edit in Hephaestus.
			await Promise.all([refreshPractice(practiceSlug), refreshSetting()]);
			const now = queryClient.getQueryData(
				getPracticeOptions({ path: { workspaceSlug, practiceSlug } }).queryKey,
			);
			if (now?.editedIn === "REPOSITORY") {
				settle(pending, undefined);
				return;
			}
			settle(pending, {
				kind: "move",
				level,
				practiceSlug,
				phase: "refused",
				reason: problemDetailOf(error),
				problems: practiceFolderProblemsOf(error),
			});
			return;
		}
		await refreshPractice(practiceSlug);
		// A failed reload leaves a practice that still reads as editable here, so the dialog keeps it
		// as a refusal, and trying again reloads.
		const reload = queryClient.getQueryState(
			getPracticeQueryKey({ path: { workspaceSlug, practiceSlug } }),
		);
		if (reload?.status === "error") {
			settle(pending, {
				kind: "move",
				level,
				practiceSlug,
				phase: "refused",
				reason: problemDetailOf(
					reload.error,
					"The practice moved, but it did not reload. Try again to reload it.",
				),
				problems: [],
			});
			return;
		}
		// The reload is in the cache, so the render this schedules shows the practice read-only: the
		// dialog closes in the same render that shows Edit in Hephaestus, and its final focus finds it.
		settle(pending, undefined);
		toast.success("This practice is now edited in the practice repository");
	};

	// A dialog asks to close on Cancel, Escape or a press outside. While its request runs, it stays.
	const cancel = () => setRequest((open) => (open?.phase === "pending" ? open : undefined));

	let state: PracticeRepositoryCardState;
	if (repository === null) {
		state = { status: "empty" };
	} else if (repository !== undefined) {
		state = { status: "set", repository };
	} else if (repositoryQuery.isError) {
		state = {
			status: "error",
			error: repositoryQuery.error,
			onRetry: () => {
				void repositoryQuery.refetch();
			},
		};
	} else {
		state = { status: "loading" };
	}

	let repositories: MonitoredRepositoryOptions;
	if (repositoriesQuery.isError) {
		repositories = {
			status: "error",
			error: repositoriesQuery.error,
			onRetry: () => {
				void repositoriesQuery.refetch();
			},
		};
	} else if (repositoriesQuery.isPending || repositoriesQuery.hasNextPage) {
		repositories = { status: "loading" };
	} else {
		// A repository the workspace removed keeps its item, but the workspace no longer monitors it.
		// One with no record behind it has only the kind's word for a label.
		const monitored = new Map(
			loadedPages(repositoriesQuery.data)
				.flatMap((page) => page.content)
				.filter((item) => item.removedAt === undefined || item.removedAt === null)
				.map((item) => [
					item.id,
					item.provider === undefined ? `Repository ${item.id}` : item.label,
				]),
		);
		repositories = {
			status: "ready",
			options: [...monitored].map(([itemId, name]) => ({ itemId, name })),
		};
	}

	const setting: PracticeRepositorySettingControl = {
		request: request?.kind === "setting" ? request : undefined,
		repositories,
		onOpen: (action) => setRequest({ kind: "setting", action, phase: "confirming" }),
		onCancel: cancel,
		onSave: (body: SetPracticeRepositoryRequest) => {
			const action =
				request?.kind === "setting" && request.action === "change" ? "change" : "set";
			void changeSetting(action, async () => set.mutateAsync({ path: { workspaceSlug }, body }));
		},
		onUnset: () => {
			void changeSetting("unset", async () => {
				await unset.mutateAsync({ path: { workspaceSlug } });
				return null;
			});
		},
	};

	return {
		card: { state, setting },
		/**
		 * Move to repository as one level runs it. `level` names the level, so the request ends when
		 * that level closes.
		 */
		move: (level: string, practiceSlug: string): MoveToRepositoryControl => ({
			repository,
			request: request?.kind === "move" && request.level === level ? request : undefined,
			onOpen: () => setRequest({ kind: "move", level, practiceSlug, phase: "confirming" }),
			onCancel: cancel,
			onConfirm: () => {
				void moveToRepository(level, practiceSlug);
			},
		}),
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
