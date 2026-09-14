import { ArrowRight } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";

import { Button } from "@/components/ui/button";

type ButtonSize = ComponentPropsWithoutRef<typeof Button>["size"];

interface LandingSignInCtaProps {
	onSignIn: () => void;
	size?: ButtonSize;
	className?: string;
}

export function LandingSignInCta({ onSignIn, size = "lg", className }: LandingSignInCtaProps) {
	return (
		<Button size={size} className={className} onClick={onSignIn}>
			Sign in <ArrowRight data-icon="inline-end" />
		</Button>
	);
}
