import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";

import { listIdentityProvidersOptions } from "@/api/@tanstack/react-query.gen";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type ButtonSize = ComponentPropsWithoutRef<typeof Button>["size"];

interface LandingSignInCtaProps {
	onSignIn: (idpHint: string) => void;
	size?: ButtonSize;
	className?: string;
}

/**
 * One call-to-action instead of a wall of provider buttons repeated across every section: `/login`
 * stays the single place a provider is chosen, so a visitor never picks one twice.
 */
export function LandingSignInCta({ onSignIn, size = "lg", className }: LandingSignInCtaProps) {
	const navigate = useNavigate();
	const { data: providers } = useQuery(listIdentityProvidersOptions());

	const handleSignIn = () => {
		// One provider → straight to OAuth (no pointless picker). Several (or not yet known) → the
		// focused /login screen, which presents each provider with proper branding and error handling.
		const soleProvider = providers?.length === 1 ? providers[0]?.registrationId : undefined;
		if (soleProvider) {
			onSignIn(soleProvider);
		} else {
			void navigate({ to: "/login" });
		}
	};

	return (
		<Button size={size} className={cn("gap-2", className)} onClick={handleSignIn}>
			Sign in <ArrowRight className="h-4 w-4" />
		</Button>
	);
}
