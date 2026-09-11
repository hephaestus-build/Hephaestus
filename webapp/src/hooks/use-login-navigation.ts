import { useLocation, useNavigate } from "@tanstack/react-router";

/** Keep the public page mounted; shared and reloaded URLs fall back to the standalone login. */
export function useLoginNavigation() {
	const location = useLocation();
	const navigate = useNavigate();
	return () => {
		void navigate({
			to: ".",
			search: (previous) => ({ ...previous, login: true }),
			mask: { to: "/login", search: { returnTo: location.href }, unmaskOnReload: true },
			resetScroll: false,
		});
	};
}
