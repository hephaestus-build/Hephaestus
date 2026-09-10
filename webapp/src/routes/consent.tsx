import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";

import {
	completeFirstLoginConsentMutation,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { ConsentPage, type ConsentSubmission } from "@/components/auth/ConsentPage";
import { useAuth } from "@/integrations/auth/AuthContext";
import { resolveCurrentUser, safeReturnTo } from "@/integrations/auth/guard";

interface ConsentSearch {
	returnTo?: string;
}

export const Route = createFileRoute("/consent")({
	staticData: { surface: "auth" },
	validateSearch: (search): ConsentSearch => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
	}),
	beforeLoad: async ({ context, search }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (!user)
			throw redirect({ to: "/login", search: { returnTo: safeReturnTo(search.returnTo) } });
		// The page owns retry and sign-out on failure; a loader error would bypass both.
		const consent = await context.queryClient
			.query(getConsentStatusOptions({}))
			.catch(() => undefined);
		if (consent?.completed) throw redirect({ href: safeReturnTo(search.returnTo) });
	},
	component: ConsentRoute,
});

function ConsentRoute() {
	const { returnTo } = Route.useSearch();
	const navigate = useNavigate();
	const queryClient = useQueryClient();
	const { logout } = useAuth();
	// The setup wording ships in the bundle, so a bundle the server has moved past is replaced by a
	// document load and by nothing the router can do.
	const reload = () => window.location.reload();
	const { data, isError, error, refetch } = useQuery(getConsentStatusOptions({}));
	const mutation = useMutation({
		...completeFirstLoginConsentMutation(),
		onError: () => {
			// A notice may have changed while it was open. Refresh it without retrying a consent write.
			void queryClient.invalidateQueries({ queryKey: getConsentStatusQueryKey({}) });
		},
		onSuccess: (status) => {
			queryClient.setQueryData(getConsentStatusQueryKey({}), status);
		},
	});

	useEffect(() => {
		if (data?.completed) void navigate({ href: safeReturnTo(returnTo), replace: true });
	}, [data?.completed, navigate, returnTo]);

	if (isError)
		return (
			<ConsentPage
				state={{ status: "error", error, onRetry: () => void refetch() }}
				onSignOut={() => void logout()}
				onReload={reload}
			/>
		);
	if (!data)
		return (
			<ConsentPage
				state={{ status: "loading" }}
				onSignOut={() => void logout()}
				onReload={reload}
			/>
		);

	const submission: ConsentSubmission = mutation.isPending
		? { status: "saving" }
		: mutation.isError && mutation.variables.body.noticeVersion === data.noticeVersion
			? { status: "error" }
			: { status: "idle" };
	return (
		<ConsentPage
			key={data.noticeVersion}
			state={{
				status: "ready",
				notice: data,
				submission,
				onSubmit: (choice) => mutation.mutate({ body: choice }),
			}}
			onSignOut={() => void logout()}
			onReload={reload}
		/>
	);
}
