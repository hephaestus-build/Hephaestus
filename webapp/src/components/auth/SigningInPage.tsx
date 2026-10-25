import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { useSpinDelay } from "spin-delay";

import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

export interface SigningInPageProps {
	/** Where the sign-in page sends the reader once they sign in again. */
	returnTo: string;
}

/**
 * The return trip from a provider, while the session is checked. No skeleton: the page has no shape
 * to mirror, because it leaves as soon as the check ends.
 *
 * The status is plain text, not a `role="status"` region: it is on the page from the first render,
 * and a live region announces only a change after it exists. The page title says the same.
 *
 * The way back appears only once the check is slow, so a fast return does not flash a choice that
 * competes with it. It is hidden rather than left out, so its arrival moves nothing.
 */
export function SigningInPage({ returnTo }: SigningInPageProps) {
	// `ssr: false`: the default shows the delayed state on the first render.
	const slow = useSpinDelay(true, { delay: 1000, ssr: false });
	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6">
			<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
			<p className="flex items-center gap-2 text-sm text-muted-foreground">
				<Spinner />
				Signing you in…
			</p>
			<Link
				to="/login"
				search={{ returnTo }}
				className={cn(buttonVariants({ variant: "outline", size: "sm" }), !slow && "invisible")}
			>
				Back to sign in
			</Link>
		</div>
	);
}
