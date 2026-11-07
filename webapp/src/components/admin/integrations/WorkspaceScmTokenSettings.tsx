import { useId, useState } from "react";
import type { ConnectionDetail } from "@/api/types.gen";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatCalendarDate } from "@/lib/dates";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";

import { IntegrationCardHeading } from "./IntegrationCardHeading";

interface WorkspaceScmTokenSettingsProps {
	providerLabel: string;
	reconnect?: boolean;
	isSaving: boolean;
	error: unknown;
	tokenExpiresAt?: Date | string | null;
	tokenExpiryCheckedAt?: Date | null;
	attentionProblem?: ConnectionDetail["attentionProblem"] | null;
	isLoadingTokenMetadata?: boolean;
	tokenMetadataError?: unknown;
	onSave: (token: string) => Promise<boolean>;
}

export function WorkspaceScmTokenSettings({
	providerLabel,
	reconnect = false,
	isSaving,
	error,
	onSave,
	tokenExpiresAt,
	tokenExpiryCheckedAt,
	attentionProblem,
	isLoadingTokenMetadata = false,
	tokenMetadataError,
}: WorkspaceScmTokenSettingsProps) {
	const id = useId();
	let buttonLabel = isSaving ? "Replacing token…" : "Replace token";
	if (reconnect) {
		buttonLabel = isSaving ? "Reconnecting…" : `Reconnect ${providerLabel}`;
	}
	const isGitLab = providerLabel === "GitLab";
	const expiresAt = asDate(tokenExpiresAt);
	// Only GitLab records a checked expiry, so a missing date proves "no expiry" for GitLab alone.
	let expiryDescription = isGitLab
		? "The token expiry is not available yet."
		: `The token expiry is not available for this connection. Check the token in ${providerLabel} and replace it below if it has expired or been revoked.`;
	if (isGitLab && tokenExpiryCheckedAt && tokenExpiresAt == null) {
		expiryDescription = "The token has no expiry.";
	}
	if (expiresAt) {
		expiryDescription = `The token expires on ${formatCalendarDate(expiresAt)}.`;
	}
	if (tokenMetadataError != null) {
		expiryDescription = "We could not load the token expiry.";
	}

	const [token, setToken] = useState("");
	const handleSave = async () => {
		if (!token.trim() || isSaving) {
			return;
		}
		if (await onSave(token.trim())) {
			setToken("");
		}
	};

	return (
		<Card>
			<CardHeader>
				<IntegrationCardHeading>Personal access token</IntegrationCardHeading>
				<CardDescription>
					{reconnect
						? `Reconnect this workspace to the same ${providerLabel} group with a new token. Hephaestus syncs the work again. If you used selected projects, select them again after reconnecting.`
						: `Replace the token used for this workspace’s ${providerLabel} connection. Existing repositories and synced work are kept.`}
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				{!reconnect && (
					<div className="space-y-3">
						{isLoadingTokenMetadata ? (
							<Skeleton className="h-4 w-52" />
						) : (
							<p className="text-sm text-muted-foreground">{expiryDescription}</p>
						)}
						{attentionProblem === "CREDENTIAL_REVOKED" && (
							<Alert variant="destructive">
								<AlertTitle>{providerLabel} no longer accepts this token</AlertTitle>
								<AlertDescription>
									Replace the token below to restore sync. Existing repositories and synced work are
									kept.
								</AlertDescription>
							</Alert>
						)}
						{isGitLab && attentionProblem === "CREDENTIAL_EXPIRING" && (
							<Alert>
								<AlertTitle>The GitLab token is about to expire</AlertTitle>
								<AlertDescription>
									Hephaestus could not rotate it. Replace it below to keep sync working.
								</AlertDescription>
							</Alert>
						)}
					</div>
				)}
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
							aria-describedby={`${id}-description${error == null ? "" : ` ${id}-error`}`}
						/>
						<FieldDescription id={`${id}-description`}>
							Use a token that can reach the same repositories and has the permissions that your
							integration needs. Hephaestus never shows the current token.
						</FieldDescription>
						{error != null && (
							<FieldError id={`${id}-error`}>
								{problemDetailOf(
									error,
									reconnect
										? "We could not reconnect GitLab. Try again."
										: "We could not replace the token. Try again.",
								)}
							</FieldError>
						)}
					</Field>
					<Button type="submit" disabled={!token.trim() || isSaving}>
						{isSaving && <Spinner />}
						{buttonLabel}
					</Button>
				</form>
			</CardContent>
		</Card>
	);
}
