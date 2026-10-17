import { type UseQueryResult, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getPublicActivityPolicyOptions,
	getPublicActivityHiddenCountOptions,
	getPublicActivityHiddenCountQueryKey,
	getWorkspacePublicActivitySettingsOptions,
	getWorkspacePublicActivitySettingsQueryKey,
	listHiddenContributorsOptions,
	listHiddenContributorsQueryKey,
	listWorkspacesQueryKey,
	updatePublicActivityMutation,
	updatePublicActivityObjectionMutation,
} from "@/api/@tanstack/react-query.gen";
import type { UpdateWorkspacePublicActivityRequest, Workspace } from "@/api/types.gen";
import type { WorkspacePublicActivityState } from "@/components/admin/settings/WorkspacePublicActivitySettings";
import { panelState } from "@/components/common/panel-state";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { problemDetailOf } from "@/lib/problem-detail";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

/**
 * A workspace's public activity page as its admin settings show it: whether it is on, whether search
 * engines may list it, how many people it leaves out, and the two changes. `live` is the
 * server-computed publication status from the workspace list.
 * An instance administrator outside that list reads the existing instance policy instead.
 */
export function useWorkspacePublicActivity({
	workspaceSlug,
	live,
	workspaceStatus,
}: {
	workspaceSlug: string | undefined;
	live: boolean | undefined;
	workspaceStatus?: Workspace["status"];
}) {
	const queryClient = useQueryClient();
	const policyOptions = getPublicActivityPolicyOptions();
	const policyQuery = useQuery({
		...policyOptions,
		enabled: workspaceSlug !== undefined && live === undefined && workspaceStatus !== undefined,
	});
	useQueryReadRefusal(policyOptions.queryKey, policyQuery.isError, policyQuery.error);
	const publicationPolicy = lastRead(policyQuery);
	const path = { workspaceSlug: workspaceSlug ?? "" };
	const settingsOptions = getWorkspacePublicActivitySettingsOptions({ path });
	const settings = useQuery({ ...settingsOptions, enabled: workspaceSlug !== undefined });
	useQueryReadRefusal(settingsOptions.queryKey, settings.isError, settings.error);
	const current = lastRead(settings);
	const hiddenOptions = getPublicActivityHiddenCountOptions({ path });
	const hidden = useQuery({ ...hiddenOptions, enabled: current.data?.enabled === true });
	useQueryReadRefusal(hiddenOptions.queryKey, hidden.isError, hidden.error);
	const hiddenContributorsOptions = listHiddenContributorsOptions({ path });
	const hiddenContributors = useQuery({
		...hiddenContributorsOptions,
		enabled: workspaceSlug !== undefined,
	});
	useQueryReadRefusal(
		hiddenContributorsOptions.queryKey,
		hiddenContributors.isError,
		hiddenContributors.error,
	);
	// Each write names, and refreshes, the workspace it was submitted for: the reply can arrive after
	// the admin has moved to another workspace.
	const showAgain = useMutation({
		...updatePublicActivityObjectionMutation(),
		onSuccess: (_data, { path: { workspaceSlug: submitted } }) => {
			const workspace = { path: { workspaceSlug: submitted } };
			void queryClient.invalidateQueries({ queryKey: listHiddenContributorsQueryKey(workspace) });
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey(workspace),
			});
			toast.success("Shown in activity again", { description: inWorkspace(submitted) });
		},
		onError: (error, { path: { workspaceSlug: submitted } }) =>
			toast.error(problemDetailOf(error, "We could not show this person again. Try again."), {
				description: inWorkspace(submitted),
			}),
	});
	const update = useMutation({
		...updatePublicActivityMutation(),
		onSuccess: async (_data, { path: submitted, body }) => {
			// The list carries the publication status, and the count follows what the page lists.
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey({ path: submitted }),
			});
			announce(body, submitted.workspaceSlug);
			// The saved settings reach the page only through a fresh read, so a read that is refused
			// by the time the change returns stays refused.
			await queryClient.invalidateQueries({
				queryKey: getWorkspacePublicActivitySettingsQueryKey({ path: submitted }),
			});
		},
		onError: (error, { path: submitted }) => {
			void queryClient.invalidateQueries({
				queryKey: getWorkspacePublicActivitySettingsQueryKey({ path: submitted }),
			});
			toast.error(
				problemDetailOf(error, "We could not change the public activity page. Try again."),
				{ description: inWorkspace(submitted.workspaceSlug) },
			);
		},
	});

	const change = (patch: Partial<UpdateWorkspacePublicActivityRequest>) => {
		if (current.data === undefined) {
			return;
		}
		const body = {
			publicActivityEnabled: current.data.enabled,
			allowSearchEngines: current.data.allowSearchEngines,
			...patch,
		};
		// A page that is off has no search engine choice: the server forgets it, so this says the same.
		update.mutate({
			path,
			body: { ...body, allowSearchEngines: body.publicActivityEnabled && body.allowSearchEngines },
		});
	};

	// A failed background read keeps the last restoration controls only while it can be retried.
	const contributors = lastRead(hiddenContributors);
	const hiddenContributorsState =
		contributors.data === undefined
			? panelState(hiddenContributors, (people) => ({ status: "ready" as const, people }))
			: {
					status: "ready" as const,
					people: contributors.data,
					refreshFailure: contributors.refreshFailure,
				};

	let state: WorkspacePublicActivityState = { status: "loading" };
	const publication =
		live ??
		(publicationPolicy.data !== undefined &&
		current.data !== undefined &&
		workspaceStatus !== undefined
			? publicationPolicy.data.allowed && workspaceStatus === "ACTIVE" && current.data.enabled
			: undefined);
	if (current.data !== undefined && publication !== undefined) {
		const pendingBody = update.isPending ? update.variables.body : undefined;
		state = {
			status: "ready",
			enabled: current.data.enabled,
			allowSearchEngines: current.data.allowSearchEngines,
			refreshFailure:
				current.refreshFailure ??
				(live === undefined ? publicationPolicy.refreshFailure : undefined),
			live: publication,
			// A count that did not load is left out rather than shown from an earlier read.
			hiddenPeople: hidden.isError ? undefined : hidden.data?.hiddenPeople,
			hiddenContributors: hiddenContributorsState,
			restoring: showAgain.isPending ? showAgain.variables.path.userId : undefined,
			pending: pendingBody && pendingChange(pendingBody, current.data.enabled),
		};
	} else if (settings.isError || (live === undefined && policyQuery.isError)) {
		state = {
			status: "error",
			error: settings.error ?? policyQuery.error,
			onRetry: () => {
				void settings.refetch();
				if (live === undefined) {
					void policyQuery.refetch();
				}
			},
		};
	}
	return {
		state,
		onEnabledChange: (publicActivityEnabled: boolean) => change({ publicActivityEnabled }),
		onSearchEnginesChange: (allowSearchEngines: boolean) => change({ allowSearchEngines }),
		onShowAgain: (userId: number) =>
			showAgain.mutate({ path: { ...path, userId }, query: { hidden: false } }),
	};
}

/**
 * What the settings may show from a read. A retryable failed refresh keeps the last answer with the
 * failure beside it. A refusal, or a read that never succeeded, shows nothing.
 */
function lastRead<TData, TError>(
	query: UseQueryResult<TData, TError>,
): {
	data: TData | undefined;
	refreshFailure?: RefreshFailure;
} {
	if (query.isRefetchError && queryErrorIsRetryable(query.error)) {
		return {
			data: query.data,
			refreshFailure: {
				error: query.error,
				pending: query.isFetching,
				onRetry: () => {
					void query.refetch();
				},
			},
		};
	}
	return { data: query.isError ? undefined : query.data };
}

/** The page's switch if the request turns it on or off, else the search engines' one. */
function pendingChange(
	{ publicActivityEnabled }: UpdateWorkspacePublicActivityRequest,
	enabled: boolean,
): "enabled" | "search-engines" {
	return publicActivityEnabled === enabled ? "search-engines" : "enabled";
}

/** Which workspace a notification is about, since the admin may be looking at another one by then. */
function inWorkspace(workspaceSlug: string): string {
	return `In workspace ${workspaceSlug}.`;
}

/** Says what was saved: whether the page is public is the workspace list's to tell, after this. */
function announce(
	{ publicActivityEnabled }: UpdateWorkspacePublicActivityRequest,
	workspaceSlug: string,
) {
	if (publicActivityEnabled) {
		toast.success("Public activity settings saved", { description: inWorkspace(workspaceSlug) });
		return;
	}
	toast.success("The public activity page is off", {
		description: `${inWorkspace(workspaceSlug)} Visitors see the change when they load the page again, at most about a minute later.`,
	});
}
