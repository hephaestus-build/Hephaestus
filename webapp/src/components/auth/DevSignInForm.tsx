import { useState } from "react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { authClient } from "@/integrations/auth/auth-client";

/**
 * Passwordless dev/test sign-in form, rendered by {@link SignInButtons} only when the server advertises
 * the `dev` provider (the `hephaestus.auth.dev-login-enabled` flag — off and fail-closed in prod). Mints
 * a real admin session for a local account so local dev and live (Playwright) E2E can authenticate
 * without an OAuth IdP. Never present in a production build's discovery list.
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
			{/* aria-live so the dev-form error is announced, mirroring the sign-in page's OAuth-error region. */}
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
