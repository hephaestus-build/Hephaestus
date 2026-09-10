import { useState } from "react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { authClient } from "@/integrations/auth/auth-client";

/**
 * Passwordless dev/test sign-in, shown only when the server advertises the `dev` provider. It mints a
 * real admin session, so local development and the live E2E suite can authenticate without an OAuth
 * provider; the server decides whether it is offered, and does not offer it in production.
 */
export function DevSignInForm({ returnTo }: { returnTo?: string }) {
	const [username, setUsername] = useState("dev-admin");
	const [pending, setPending] = useState(false);
	const [error, setError] = useState<string>();

	const submit = async () => {
		const name = username.trim() || "dev-admin";
		setPending(true);
		setError(undefined);
		try {
			await authClient.devLogin(name, true, returnTo);
		} catch (e) {
			setError(e instanceof Error ? e.message : "Dev sign-in failed");
			setPending(false);
		}
	};

	return (
		<div className="flex flex-col gap-2 rounded-md border border-dashed border-amber-500/50 bg-amber-500/5 p-3">
			<p className="text-xs font-medium text-muted-foreground">Dev sign-in (non-production)</p>
			{/* aria-live: the error arrives after the button is pressed, so nothing announces it. */}
			<div aria-live="assertive" aria-atomic="true">
				{error ? (
					<Alert variant="destructive">
						<AlertDescription>{error}</AlertDescription>
					</Alert>
				) : null}
			</div>
			<form
				className="flex flex-col gap-2"
				onSubmit={(e) => {
					e.preventDefault();
					void submit();
				}}
			>
				<Input
					aria-label="Dev username"
					placeholder="username"
					value={username}
					disabled={pending}
					onChange={(e) => setUsername(e.target.value)}
				/>
				<Button type="submit" variant="outline" disabled={pending} className="w-full">
					Continue as dev admin
				</Button>
			</form>
		</div>
	);
}
