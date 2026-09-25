import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	dismissMemberOnboardingMutation,
	getAccountAiChoiceQueryKey,
	getMemberOnboardingOptions,
	getMemberOnboardingQueryKey,
	updateMemberAiChoiceMutation,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import {
	type OnboardingAction,
	type OnboardingSubmission,
	type WorkspaceOnboardingPageProps,
	WorkspaceOnboardingPage,
} from "@/components/onboarding/WorkspaceOnboardingPage";
import type { MemberAiChoice } from "@/components/practice-vocabulary/data-handling-defs";
import { memberOnboardingQueryScope } from "@/hooks/use-member-onboarding";
import { openRequiredLinks } from "@/lib/onboarding-links";
import { problemDetailOf } from "@/lib/problem-detail";
import { useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/guard";

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
	) {
		return base;
	}
	return `${url.pathname}${url.search}${url.hash}`;
}

interface Tracked {
	action: OnboardingAction;
	mutation: { isPending: boolean; isError: boolean; error: unknown; submittedAt: number };
}

/** The page shows one submission at a time: whichever mutation was fired last. */
function submissionOf(tracked: readonly [Tracked, ...Tracked[]]): OnboardingSubmission {
	let latest = tracked[0];
	for (const candidate of tracked) {
		if (candidate.mutation.submittedAt >= latest.mutation.submittedAt) {
			latest = candidate;
		}
	}
	if (latest.mutation.isPending) {
		return { status: "saving", action: latest.action };
	}
	if (latest.mutation.isError) {
		return {
			status: "error",
			action: latest.action,
			message: problemDetailOf(latest.mutation.error),
		};
	}
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
	const choice = useMutation({
		...updateMemberAiChoiceMutation(),
		onSuccess: (data, variables) => {
			updateCache(data, variables);
			// The answer is the account's: User settings and every other workspace's setup page read it.
			void queryClient.invalidateQueries({ queryKey: getAccountAiChoiceQueryKey({}) });
			// This workspace's entry was just set from the response; the others refetch when next shown.
			void queryClient.invalidateQueries({
				queryKey: memberOnboardingQueryScope(),
				refetchType: "none",
			});
		},
	});
	const dismissal = useMutation({ ...dismissMemberOnboardingMutation(), onSuccess: updateCache });
	const submission = submissionOf([
		{ action: "save", mutation: choice },
		{ action: "continue", mutation: dismissal },
	]);
	const { data } = query;

	// Every step after a write is a `mutate` callback rather than an awaited promise: those callbacks
	// are dropped once this route unmounts, so a save that finishes after the reader has moved to
	// another workspace cannot navigate them or redirect them from there. A failure stays in the
	// mutation's own state, which is what the page reads.
	//
	// Setup is done when nothing is owed, which the server reports as `needsSetup: false` right after
	// the answer is saved; there is no completion to record, so Continue simply leaves.
	const finish = (current: WorkspaceOnboarding) => {
		if (openRequiredLinks(current.links).length > 0) {
			return;
		}
		leave();
	};
	const submit = (current: WorkspaceOnboarding, value: MemberAiChoice) => {
		if (value === current.aiChoice) {
			finish(current);
		} else {
			choice.mutate({ path, body: { choice: value } }, { onSuccess: finish });
		}
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
		if (draft && draft !== current.aiChoice) {
			choice.mutate({ path, body: { choice: draft } }, { onSuccess: redirect });
		} else {
			redirect();
		}
	};

	let state: WorkspaceOnboardingPageProps["state"];
	if (data !== undefined) {
		state = {
			status: "ready",
			data,
			submission,
			refresh: query.isError ? { status: "error", error: query.error, onRetry: retry } : undefined,
			onSubmit: (value) => submit(data, value),
			onLink: (registrationId, draft) => link(data, registrationId, draft),
			onLeave: () => (data.needsSetup ? dismissal.mutate({ path }, { onSuccess: leave }) : leave()),
		};
	} else if (query.isError) {
		state = { status: "error", error: query.error, onRetry: retry, onLeave: leave };
	} else {
		state = { status: "loading" };
	}
	return (
		<WorkspaceOnboardingPage focus={step === "accounts" ? "accounts" : undefined} state={state} />
	);
}
