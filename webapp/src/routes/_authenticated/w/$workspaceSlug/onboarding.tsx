import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	completeMemberOnboardingMutation,
	dismissMemberOnboardingMutation,
	getMemberOnboardingOptions,
	getMemberOnboardingQueryKey,
	updateMemberAiChoiceMutation,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { WorkspaceOnboardingPage } from "@/components/onboarding/WorkspaceOnboardingPage";
import { useAuth } from "@/integrations/auth/AuthContext";
import { safeReturnTo } from "@/integrations/auth/guard";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/onboarding")({
	staticData: { surface: "auth" },
	validateSearch: (search): { returnTo?: string; step?: "accounts" } => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
		step: search.step === "accounts" ? "accounts" : undefined,
	}),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: OnboardingRoute,
});

/** A setup return must stay in this workspace, including after URL path normalization. */
function workspaceReturnTo(value: string | undefined, workspaceSlug: string) {
	const base = `/w/${encodeURIComponent(workspaceSlug)}`;
	const url = new URL(safeReturnTo(value), "https://workspace.invalid");
	let pathname: string;
	try {
		pathname = decodeURIComponent(url.pathname);
	} catch {
		return base;
	}
	const workspacePath = `/w/${workspaceSlug}`;
	if (
		pathname.includes("\\") ||
		pathname.includes("%") ||
		pathname.split("/").some((segment) => segment === "." || segment === "..") ||
		(pathname !== workspacePath && !pathname.startsWith(`${workspacePath}/`)) ||
		pathname === `${workspacePath}/onboarding` ||
		pathname.startsWith(`${workspacePath}/onboarding/`)
	)
		return base;
	return `${url.pathname}${url.search}${url.hash}`;
}

function OnboardingRoute() {
	const { workspaceSlug } = Route.useParams();
	const navigate = Route.useNavigate();
	const { returnTo, step } = Route.useSearch();
	const destination = workspaceReturnTo(returnTo, workspaceSlug);
	const queryClient = useQueryClient();
	const { linkAccount } = useAuth();
	const path = { workspaceSlug };
	const query = useQuery(getMemberOnboardingOptions({ path }));
	const updateCache = (data: WorkspaceOnboarding, variables: { path: { workspaceSlug: string } }) =>
		queryClient.setQueryData(getMemberOnboardingQueryKey({ path: variables.path }), data);
	const leave = () => {
		void navigate({ href: destination, replace: true });
	};
	const refreshOnConflict = async (
		error: unknown,
		variables: { path: { workspaceSlug: string } },
	) => {
		if (problemStatusOf(error) === 409)
			await queryClient.invalidateQueries({
				queryKey: getMemberOnboardingQueryKey({ path: variables.path }),
			});
	};
	const choice = useMutation({
		...updateMemberAiChoiceMutation(),
		onSuccess: updateCache,
		onError: refreshOnConflict,
	});
	const completion = useMutation({
		...completeMemberOnboardingMutation(),
		onSuccess: updateCache,
		onError: refreshOnConflict,
	});
	const dismissal = useMutation({ ...dismissMemberOnboardingMutation(), onSuccess: updateCache });
	const error = choice.error ?? completion.error ?? dismissal.error;
	return (
		<WorkspaceOnboardingPage
			initialStep={step === "accounts" ? "accounts" : "choice"}
			state={
				query.data
					? {
							status: "ready",
							data: query.data,
							refresh: query.isFetching
								? { status: "pending" }
								: query.isError
									? { status: "error", error: query.error, onRetry: () => void query.refetch() }
									: undefined,
						}
					: query.isError
						? { status: "error", error: query.error, onRetry: () => void query.refetch() }
						: { status: "loading" }
			}
			pending={
				choice.isPending
					? "choice"
					: completion.isPending
						? "completion"
						: dismissal.isPending
							? "dismissal"
							: undefined
			}
			saveError={error ? problemDetailOf(error) : undefined}
			onChoose={async (value) => {
				completion.reset();
				dismissal.reset();
				try {
					await choice.mutateAsync({ path, body: { choice: value } });
					return true;
				} catch {
					return false;
				}
			}}
			onComplete={() => {
				choice.reset();
				dismissal.reset();
				if (query.data)
					completion.mutate(
						{ path, body: { revision: query.data.revision } },
						{ onSuccess: leave },
					);
			}}
			onDismiss={() => {
				choice.reset();
				completion.reset();
				dismissal.mutate({ path }, { onSuccess: leave });
			}}
			onRefresh={() => {
				void query.refetch();
			}}
			onLink={(registrationId) =>
				linkAccount(
					registrationId,
					`/w/${encodeURIComponent(workspaceSlug)}/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
				)
			}
		/>
	);
}
