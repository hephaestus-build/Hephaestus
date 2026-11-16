import { CircleCheckIcon, EyeIcon, EyeOffIcon, OctagonXIcon } from "lucide-react";
import { useState } from "react";

import type { GitLabPreflightRequest } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupInput,
} from "@/components/ui/input-group";
import { isComposing } from "@/lib/keyboard-shortcuts";
import { firstNonBlank, hasText } from "@/lib/text";

import { connectionSchema } from "./schemas";
import { useWizard } from "./wizard-context";

export interface ConnectGitLabStepProps {
	/** A check of the token is under way. */
	validating?: boolean;
	/** Why the last check of the current token got no answer, in words for the reader. */
	checkFailure?: string;
	/** Checks the token against the instance. The wizard state receives the answer. */
	onValidate: (request: GitLabPreflightRequest) => void;
}

export function ConnectGitLabStep({
	validating = false,
	checkFailure,
	onValidate,
}: ConnectGitLabStepProps) {
	const { state, dispatch } = useWizard();
	const [showToken, setShowToken] = useState(false);
	const [tokenError, setTokenError] = useState<string>();

	const handleValidate = () => {
		if (validating) {
			return;
		}
		const result = connectionSchema.safeParse({
			personalAccessToken: state.personalAccessToken,
		});
		if (!result.success) {
			setTokenError(result.error.issues[0]?.message);
			return;
		}
		setTokenError(undefined);
		// Persist the trimmed token so downstream steps use normalized data
		dispatch({ type: "SET_PAT", value: result.data.personalAccessToken });
		onValidate({
			personalAccessToken: result.data.personalAccessToken,
			serverUrl: state.serverUrl,
		});
	};

	return (
		<div className="flex flex-col gap-4">
			<Field>
				<FieldLabel htmlFor="gitlab-server-url">GitLab instance</FieldLabel>
				<Input
					id="gitlab-server-url"
					value={state.serverUrl}
					disabled
					aria-describedby="gitlab-server-url-description"
				/>
				<FieldDescription id="gitlab-server-url-description">
					The GitLab instance Hephaestus reads from, set by an instance admin.
				</FieldDescription>
			</Field>

			<Field data-invalid={hasText(tokenError) ? "true" : undefined}>
				<FieldLabel htmlFor="gitlab-pat">Access token</FieldLabel>
				<InputGroup>
					<InputGroupInput
						id="gitlab-pat"
						type={showToken ? "text" : "password"}
						placeholder="glpat-... or glgat-..."
						value={state.personalAccessToken}
						onChange={(e) => dispatch({ type: "SET_PAT", value: e.target.value })}
						autoComplete="off"
						aria-required="true"
						aria-invalid={hasText(tokenError)}
						aria-describedby={hasText(tokenError) ? "gitlab-pat-error" : "gitlab-pat-description"}
						onKeyDown={(e) => {
							if (e.key === "Enter" && !isComposing(e.nativeEvent)) {
								e.preventDefault();
								handleValidate();
							}
						}}
					/>
					<InputGroupAddon align="inline-end">
						<InputGroupButton
							type="button"
							size="icon-xs"
							onClick={() => setShowToken(!showToken)}
							aria-label={showToken ? "Hide token" : "Show token"}
							aria-pressed={showToken}
						>
							{showToken ? <EyeOffIcon /> : <EyeIcon />}
						</InputGroupButton>
					</InputGroupAddon>
				</InputGroup>
				<FieldDescription id="gitlab-pat-description">
					Use a{" "}
					<InlineLink
						href={`${state.serverUrl}/help/user/group/settings/group_access_tokens`}
						external
					>
						group access token
					</InlineLink>{" "}
					with the <strong>Owner</strong> role and the <code className="text-xs">api</code> scope.
					Webhook registration needs the Owner role. Turn on token rotation for long-lived setups.
				</FieldDescription>
				{hasText(tokenError) && <FieldError id="gitlab-pat-error">{tokenError}</FieldError>}
			</Field>

			<Button
				pending={validating}
				type="button"
				onClick={handleValidate}
				disabled={!state.personalAccessToken.trim() || validating}
			>
				Validate token
			</Button>

			{state.preflightResult?.valid === true && (
				<Alert>
					<CircleCheckIcon aria-hidden="true" className="text-success" />
					<AlertTitle>Token valid</AlertTitle>
					<AlertDescription>
						Authenticated as <strong>{state.preflightResult.username}</strong>
					</AlertDescription>
				</Alert>
			)}

			{state.preflightResult && !state.preflightResult.valid && (
				<Alert variant="destructive">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Token validation failed</AlertTitle>
					<AlertDescription>
						{firstNonBlank(state.preflightResult.error) ??
							"We could not validate that token. Check it, then try again."}
					</AlertDescription>
				</Alert>
			)}

			{checkFailure !== undefined && (
				<Alert variant="destructive">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>We could not check that token</AlertTitle>
					<AlertDescription>{checkFailure}</AlertDescription>
				</Alert>
			)}
		</div>
	);
}
