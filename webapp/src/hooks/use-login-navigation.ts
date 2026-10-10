import { useLocation, useNavigate } from "@tanstack/react-router";

import { redirectToLogin } from "@/runtime/auth/session-expiry";
import { workspaceAddressConfig, tenantSlug } from "@/runtime/workspace-address";

/** Keep the public page mounted; shared and reloaded URLs fall back to the standalone login. */
export function useLoginNavigation() {
	const location = useLocation();
	const navigate = useNavigate();
	return () => {
		if (tenantSlug(new URL(window.location.origin), workspaceAddressConfig) !== undefined) {
			redirectToLogin();
			return;
		}
		void navigate({
			to: ".",
			search: (previous) => ({ ...previous, login: true }),
			mask: { to: "/login", search: { returnTo: location.href }, unmaskOnReload: true },
			resetScroll: false,
		});
	};
}
