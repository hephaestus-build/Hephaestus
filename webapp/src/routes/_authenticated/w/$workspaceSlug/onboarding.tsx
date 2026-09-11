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
import {
	type OnboardingAction,
	type OnboardingSubmission,
	WorkspaceOnboardingPage,
} from "@/components/onboarding/WorkspaceOnboardingPage";
import type { MemberAiChoice } from "@/components/practice-vocabulary/data-handling-defs";
import { useAuth } from "@/integrations/auth/AuthContext";
import { safeReturnTo } from "@/integrations/auth/guard";
import { openRequiredLinks } from "@/lib/onboarding-links";
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
		// A second layer of encoding survives the one decode above, so `%252e%252e` is still a dot
		// segment in waiting; `safeReturnTo` only checks the fully decoded prefix, not the segments.
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

interface Tracked {
	action: OnboardingAction;
	mutation: { isPending: boolean; isError: boolean; error: unknown; submittedAt: number };
}

/** The page shows one submission at a time: whichever mutation was fired last. */
function submissionOf(tracked: readonly [Tracked, ...Tracked[]]): OnboardingSubmission {
	const latest = tracked.reduce((current, candidate) =>
		candidate.mutation.submittedAt >= current.mutation.submittedAt ? candidate : current,
	);
	if (latest.mutation.isPending) return { status: "saving", action: latest.action };
	if (latest.mutation.isError)
		return {
			status: "error",
			action: latest.action,
			message: problemDetailOf(latest.mutation.error),
		};
	return { status: "idle" };
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
	const retry = () => {
		void query.refetch();
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
	const submission = submissionOf([
		{ action: "save", mutation: choice },
		{ action: "save", mutation: completion },
		{ action: "continue", mutation: dismissal },
	]);
	const data = query.data;

	// Every step after a write is a `mutate` callback rather than an awaited promise: those callbacks
	// are dropped once this route unmounts, so a save that finishes after the reader has moved to
	// another workspace cannot navigate them or redirect them from there. A failure stays in the
	// mutation's own state, which is what the page reads.
	const finish = (current: WorkspaceOnboarding) => {
		if (!current.needsWelcome) return;
		if (openRequiredLinks(current.links).length > 0) return;
		completion.mutate({ path, body: { revision: current.revision } }, { onSuccess: leave });
	};
	const submit = (current: WorkspaceOnboarding, value: MemberAiChoice) => {
		if (value === current.aiChoice) finish(current);
		else choice.mutate({ path, body: { choice: value } }, { onSuccess: finish });
	};
	const link = (
		current: WorkspaceOnboarding,
		registrationId: string,
		draft: MemberAiChoice | undefined,
	) => {
		const redirect = () =>
			linkAccount(
				registrationId,
				`/w/${encodeURIComponent(workspaceSlug)}/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
			);
		if (draft && draft !== current.aiChoice)
			choice.mutate({ path, body: { choice: draft } }, { onSuccess: redirect });
		else redirect();
	};

	return (
		<WorkspaceOnboardingPage
			focus={step === "accounts" ? "accounts" : undefined}
			state={
				data
					? {
							status: "ready",
							data,
							submission,
							refresh: query.isError
								? { status: "error", error: query.error, onRetry: retry }
								: undefined,
							onSubmit: (value) => submit(data, value),
							onLink: (registrationId, draft) => link(data, registrationId, draft),
							onLeave: () =>
								data.needsWelcome ? dismissal.mutate({ path }, { onSuccess: leave }) : leave(),
						}
					: query.isError
						? { status: "error", error: query.error, onRetry: retry, onLeave: leave }
						: { status: "loading" }
			}
		/>
	);
}
