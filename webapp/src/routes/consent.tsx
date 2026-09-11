import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";

import {
	completeFirstLoginConsentMutation,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { ConsentDialog } from "@/components/auth/ConsentDialog";
import { useAuth } from "@/integrations/auth/AuthContext";
import { resolveCurrentUser, safeReturnTo } from "@/integrations/auth/guard";

interface ConsentSearch {
	returnTo?: string;
}

/**
 * The transparency notice.
 *
 * It is a route because only aborting the match stops the pages below from loading, and they fetch
 * from endpoints the server refuses until the notice is answered — suppressing what renders would
 * leave those requests running. The routes that send a reader here mask the address bar to the page
 * they asked for, so what a reader sees is that page interrupted, not a trip somewhere else.
 */
export const Route = createFileRoute("/consent")({
	staticData: { surface: "auth" },
	validateSearch: (search): ConsentSearch => ({
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
	}),
	beforeLoad: async ({ context, search }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (!user)
			throw redirect({ to: "/login", search: { returnTo: safeReturnTo(search.returnTo) } });
		const consent = await context.queryClient.query(getConsentStatusOptions({}));
		if (consent.completed) throw redirect({ href: safeReturnTo(search.returnTo) });
	},
	component: ConsentPage,
});

function ConsentPage() {
	const { returnTo } = Route.useSearch();
	const navigate = useNavigate();
	const queryClient = useQueryClient();
	const { logout } = useAuth();
	const { data, isError, refetch } = useQuery(getConsentStatusOptions({}));
	const mutation = useMutation({
		...completeFirstLoginConsentMutation(),
		onSuccess: (status) => {
			queryClient.setQueryData(getConsentStatusQueryKey({}), status);
			void navigate({ href: safeReturnTo(returnTo), replace: true });
		},
	});

	return (
		<ConsentDialog
			notice={data}
			failedToLoad={isError}
			submitting={mutation.isPending}
			failedToSubmit={mutation.isError}
			onSubmit={(choice) => mutation.mutate({ body: choice })}
			onRetry={() => void refetch()}
			onSignOut={() => void logout()}
		/>
	);
}
