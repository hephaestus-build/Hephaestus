import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Outlet, redirect, useMatchRoute } from "@tanstack/react-router";

import { adminGetInstanceSettingsOptions } from "@/api/@tanstack/react-query.gen";
import { SilentModeBanner } from "@/components/admin/instance/SilentModeBanner";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { isAppAdmin, resolveCurrentUser } from "@/runtime/auth/guard";

/**
 * The client is not a security boundary — every `/admin` endpoint is enforced server-side by
 * `hasAuthority('app_admin')` (ADR 0017), so this guard only avoids a pointless flash of admin UI.
 *
 * The silent-mode banner is pinned by the layout rather than per page: an engaged brake must stay
 * visible wherever the operator navigates.
 */
export const Route = createFileRoute("/_authenticated/admin")({
	beforeLoad: async ({ context }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (!isAppAdmin(user)) {
			throw redirect({ to: "/" });
		}
	},
	component: AdminLayout,
});

function AdminLayout() {
	const settingsQuery = useQuery(adminGetInstanceSettingsOptions());
	// The settings page owns this query's error; a second alert here would just stack on it.
	const onSettingsPage = useMatchRoute()({ to: "/admin/settings" }) !== false;
	const topStrip =
		settingsQuery.data?.silentModeEngaged === true ? (
			<SilentModeBanner settings={settingsQuery.data} />
		) : settingsQuery.isError && !onSettingsPage ? (
			// Unknown delivery state is not "delivering": say so rather than silently showing nothing.
			<QueryErrorAlert
				error={settingsQuery.error}
				title="Couldn't load the instance delivery state"
				onRetry={() => void settingsQuery.refetch()}
			/>
		) : null;
	return (
		<>
			{topStrip ? <div className="mx-auto mb-6 w-full max-w-6xl">{topStrip}</div> : null}
			<Outlet />
		</>
	);
}
