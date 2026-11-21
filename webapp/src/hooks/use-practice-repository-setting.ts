import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
	getPracticeRepositoryQueryKey,
	listReviewItemsInfiniteOptions,
	setPracticeRepositoryMutation,
	unsetPracticeRepositoryMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	PracticeRepository,
	SetPracticeRepositoryError,
	SetPracticeRepositoryRequest,
} from "@/api/types.gen";
import type {
	MonitoredRepositoryOptions,
	PracticeRepositoryCardState,
	PracticeRepositoryRequest,
	PracticeRepositorySettingControl,
} from "@/components/admin/practices/WorkspacePracticeRepositoryCard";
import {
	usePracticeRepositoryRead,
	usePracticeRepositoryRefresh,
	useRecentSignIn,
} from "@/hooks/use-practice-repository";
import { ITEM_KIND } from "@/lib/item-kinds";
import { problemDetailOf, stepUpChallengeOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";
import { loadedPages, offsetPageParams } from "@/runtime/tanstack-query/list-pages";
import { useEveryPage } from "@/runtime/tanstack-query/use-every-page";

const PICKER_PAGE_SIZE = 100;

/**
 * The practice repository card on Practice setup, which sets, changes and unsets the practice
 * repository of a workspace (ADR 0118). Every write asks for a recent sign-in.
 */
export function usePracticeRepositorySetting(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const [request, setRequest] = useState<PracticeRepositoryRequest>();
	const recentSignIn = useRecentSignIn();
	const { query: repositoryQuery, repository } = usePracticeRepositoryRead(workspaceSlug);
	const { refreshSetting } = usePracticeRepositoryRefresh(workspaceSlug);

	const choosing = request !== undefined && request.action !== "unset";
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

	// Each write keeps its dialog open and busy until the page shows the result, then ends the
	// request: a dialog closed earlier would hand focus to a control that the reload replaces.
	const endSetting = (refusal?: { reason: string; directoryError?: string }) =>
		setRequest((current) => {
			if (current?.phase !== "pending") {
				return current;
			}
			return refusal === undefined
				? undefined
				: { action: current.action, phase: "refused", ...refusal };
		});

	const settingSaved = async (saved: PracticeRepository | null, toastText: string) => {
		// The answer is the setting now, so the card shows it even if the read after it fails. "None"
		// is `null`, the value the client hands over for the read's empty 204.
		queryClient.setQueryData<PracticeRepository | null>(
			getPracticeRepositoryQueryKey({ path: { workspaceSlug } }),
			saved,
		);
		await refreshSetting();
		endSetting();
		toast.success(toastText);
	};
	// Set and Unset refuse with the same problem shape, so one type covers both.
	const settingRefused = async (error: SetPracticeRepositoryError) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp !== undefined) {
			endSetting();
			recentSignIn.ask(stepUp);
			return;
		}
		// A refusal can mean the setting changed meanwhile, such as an Unset that another admin got
		// to first: show it as it now is, beside the reason.
		await refreshSetting();
		const [directoryMessage] = error.errors?.directory ?? [];
		endSetting({
			reason: problemDetailOf(error),
			directoryError:
				directoryMessage === undefined ? undefined : `The directory ${directoryMessage}.`,
		});
	};

	const set = useMutation({
		...setPracticeRepositoryMutation(),
		onSuccess: async (saved) => settingSaved(saved, "Practice repository saved"),
		onError: settingRefused,
	});
	const unset = useMutation({
		...unsetPracticeRepositoryMutation(),
		onSuccess: async () => settingSaved(null, "Practice repository unset"),
		onError: settingRefused,
	});

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
				.filter((item) => item.removedAt === undefined)
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
		request,
		repositories,
		onOpen: (action) => setRequest({ action, phase: "confirming" }),
		// A dialog asks to close on Cancel, Escape or a press outside. While its request runs, it stays.
		onCancel: () => setRequest((open) => (open?.phase === "pending" ? open : undefined)),
		onSave: (body: SetPracticeRepositoryRequest) => {
			setRequest({ action: request?.action === "change" ? "change" : "set", phase: "pending" });
			set.mutate({ path: { workspaceSlug }, body });
		},
		onUnset: () => {
			setRequest({ action: "unset", phase: "pending" });
			unset.mutate({ path: { workspaceSlug } });
		},
	};

	return { card: { state, setting }, confirmation: recentSignIn.confirmation };
}
