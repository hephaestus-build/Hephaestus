import { ArrowRight } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";

type ButtonSize = ComponentPropsWithoutRef<typeof Button>["size"];

interface LandingSignInCtaProps {
	onSignIn: () => void;
	size?: ButtonSize;
	className?: string;
}

export function LandingSignInCta({ onSignIn, size = "lg", className }: LandingSignInCtaProps) {
	return (
		<Button size={size} className={cn("gap-2", className)} onClick={onSignIn}>
			Sign in <ArrowRight className="h-4 w-4" />
		</Button>
	);
}
