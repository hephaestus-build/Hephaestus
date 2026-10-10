import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
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
import type { UpdateWorkspacePublicActivityRequest } from "@/api/types.gen";
import type { WorkspacePublicActivityState } from "@/components/admin/settings/WorkspacePublicActivitySettings";
import { problemDetailOf } from "@/lib/problem-detail";

/**
 * A workspace's public activity page as its admin settings show it: whether it is on, whether search
 * engines may list it, how many people it leaves out, and the two changes. `live` is what the
 * workspace list says, since only the instance knows whether it allows public pages.
 */
export function useWorkspacePublicActivity({
	workspaceSlug,
	live,
}: {
	workspaceSlug: string | undefined;
	live: boolean;
}) {
	const queryClient = useQueryClient();
	const path = { workspaceSlug: workspaceSlug ?? "" };
	const settings = useQuery({
		...getWorkspacePublicActivitySettingsOptions({ path }),
		enabled: workspaceSlug !== undefined,
	});
	const hidden = useQuery({
		...getPublicActivityHiddenCountOptions({ path }),
		enabled: settings.data?.enabled === true,
	});
	const hiddenContributors = useQuery({
		...listHiddenContributorsOptions({ path }),
		enabled: workspaceSlug !== undefined,
	});
	const showAgain = useMutation({
		...updatePublicActivityObjectionMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: listHiddenContributorsQueryKey({ path }) });
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey({ path }),
			});
			toast.success("Shown in activity again");
		},
		onError: (error) =>
			toast.error(problemDetailOf(error, "We could not show this person again. Try again.")),
	});
	const update = useMutation({
		...updatePublicActivityMutation(),
		onSuccess: (data, { body }) => {
			queryClient.setQueryData(getWorkspacePublicActivitySettingsQueryKey({ path }), data);
			// The list says whether the page is live, and the count follows what the page lists.
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey({ path }),
			});
			announce(body);
		},
		onError: (error) => {
			void queryClient.invalidateQueries({
				queryKey: getWorkspacePublicActivitySettingsQueryKey({ path }),
			});
			toast.error(
				problemDetailOf(error, "We could not change the public activity page. Try again."),
			);
		},
	});

	const change = (patch: Partial<UpdateWorkspacePublicActivityRequest>) => {
		if (settings.data === undefined) {
			return;
		}
		const body = {
			publicActivityEnabled: settings.data.enabled,
			allowSearchEngines: settings.data.allowSearchEngines,
			...patch,
		};
		// A page that is off has no search engine choice: the server forgets it, so this says the same.
		update.mutate({
			path,
			body: { ...body, allowSearchEngines: body.publicActivityEnabled && body.allowSearchEngines },
		});
	};

	let state: WorkspacePublicActivityState = { status: "loading" };
	if (settings.data !== undefined) {
		const pendingBody = update.isPending ? update.variables.body : undefined;
		state = {
			status: "ready",
			enabled: settings.data.enabled,
			allowSearchEngines: settings.data.allowSearchEngines,
			live,
			hiddenPeople: hidden.data?.hiddenPeople,
			hiddenContributors: hiddenContributors.data ?? [],
			restoring: showAgain.isPending ? showAgain.variables.path.userId : undefined,
			pending: pendingBody && pendingChange(pendingBody, settings.data.enabled),
		};
	} else if (settings.isError) {
		state = {
			status: "error",
			error: settings.error,
			onRetry: () => {
				void settings.refetch();
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

/** The page's switch if the request turns it on or off, else the search engines' one. */
function pendingChange(
	{ publicActivityEnabled }: UpdateWorkspacePublicActivityRequest,
	enabled: boolean,
): "enabled" | "search-engines" {
	return publicActivityEnabled === enabled ? "search-engines" : "enabled";
}

function announce({
	publicActivityEnabled,
	allowSearchEngines,
}: UpdateWorkspacePublicActivityRequest) {
	if (publicActivityEnabled) {
		toast.success(
			allowSearchEngines
				? "The public activity page is on, and search engines can list it"
				: "The public activity page is on",
		);
		return;
	}
	toast.success("The public activity page is off", {
		description:
			"Visitors see the change when they load the page again, at most about a minute later.",
	});
}
