import { useMutation } from "@tanstack/react-query";
import { useEffect } from "react";
import { toast } from "sonner";

import { exitImpersonationMutation } from "@/api/@tanstack/react-query.gen";
import { ImpersonationBanner } from "@/components/auth/ImpersonationBanner";
import { useAuth } from "@/integrations/auth/AuthContext";
import { withSessionMutationLock } from "@/integrations/auth/session-mutation";
import { useImpersonationStore } from "@/stores/impersonation-store";

export function ImpersonationBannerHost() {
	const { isImpersonating, impersonatedDisplayName } = useAuth();
	const writesEnabled = useImpersonationStore((s) => s.writesEnabled);
	const setWritesEnabled = useImpersonationStore((s) => s.setWritesEnabled);

	const exit = useMutation({
		...withSessionMutationLock(exitImpersonationMutation()),
		onSuccess: () => {
			// Discard impersonated account data before loading the operator session.
			window.location.assign("/");
		},
		onError: () => {
			// The impersonated session may still be active; disable writes until explicitly re-enabled.
			setWritesEnabled(false);
			toast.error("Could not stop impersonating. Please try again.");
		},
	});

	useEffect(() => {
		if (!isImpersonating) {
			setWritesEnabled(false);
			return;
		}
		document.body.setAttribute("data-impersonating", "true");
		return () => {
			document.body.removeAttribute("data-impersonating");
			setWritesEnabled(false);
		};
	}, [isImpersonating, setWritesEnabled]);

	if (!isImpersonating) return null;

	return (
		<ImpersonationBanner
			displayName={impersonatedDisplayName ?? "another account"}
			writesEnabled={writesEnabled}
			isExiting={exit.isPending}
			onEnableWrites={() => setWritesEnabled(true)}
			onDisableWrites={() => setWritesEnabled(false)}
			onExit={() => exit.mutate({})}
		/>
	);
}
