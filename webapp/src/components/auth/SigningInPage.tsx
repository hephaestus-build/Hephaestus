import { Link } from "@tanstack/react-router";

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
 */
export function SigningInPage({ returnTo }: SigningInPageProps) {
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
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				Back to sign in
			</Link>
		</div>
	);
}
