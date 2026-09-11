import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";

import { IntegrationCardHeading } from "./IntegrationCardHeading";

interface WorkspaceScmTokenSettingsProps {
	providerLabel: string;
	isSaving: boolean;
	error: unknown;
	onSave: (token: string) => Promise<boolean>;
}

export function WorkspaceScmTokenSettings({
	providerLabel,
	isSaving,
	error,
	onSave,
}: WorkspaceScmTokenSettingsProps) {
	const id = useId();
	const [token, setToken] = useState("");
	const handleSave = async () => {
		if (!token.trim() || isSaving) return;
		if (await onSave(token.trim())) setToken("");
	};

	return (
		<Card>
			<CardHeader>
				<IntegrationCardHeading>Personal access token</IntegrationCardHeading>
				<CardDescription>
					Replace the token used for this workspace's {providerLabel} connection. Existing
					repositories and synced work are kept.
				</CardDescription>
			</CardHeader>
			<CardContent>
				<form
					className="space-y-4"
					onSubmit={(event) => {
						event.preventDefault();
						void handleSave();
					}}
				>
					<Field>
						<FieldLabel htmlFor={id}>New personal access token</FieldLabel>
						<Input
							id={id}
							type="password"
							autoComplete="new-password"
							value={token}
							onChange={(event) => setToken(event.target.value)}
							disabled={isSaving}
							required
							aria-describedby={`${id}-description${error ? ` ${id}-error` : ""}`}
						/>
						<FieldDescription id={`${id}-description`}>
							Use a token with access to the same repositories and the permissions required by your
							integration. The current token is never displayed.
						</FieldDescription>
						{error != null && (
							<FieldError id={`${id}-error`}>
								{problemDetailOf(error, "Couldn't replace the token. Try again.")}
							</FieldError>
						)}
					</Field>
					<Button type="submit" disabled={!token.trim() || isSaving}>
						{isSaving && <Spinner />}
						{isSaving ? "Saving token…" : "Replace token"}
					</Button>
				</form>
			</CardContent>
		</Card>
	);
}
