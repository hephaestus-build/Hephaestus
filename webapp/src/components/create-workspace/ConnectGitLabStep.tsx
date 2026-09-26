import { useMutation } from "@tanstack/react-query";
import { CircleCheckIcon, EyeIcon, EyeOffIcon, OctagonXIcon } from "lucide-react";
import { useState } from "react";

import { gitLabPreflightMutation } from "@/api/@tanstack/react-query.gen";
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
import { Spinner } from "@/components/ui/spinner";
import { firstNonBlank, hasText } from "@/lib/text";

import { connectionSchema } from "./schemas";
import { isForCurrentToken, useWizard } from "./wizard-context";

export function ConnectGitLabStep() {
	const { state, dispatch } = useWizard();
	const [showToken, setShowToken] = useState(false);
	const [tokenError, setTokenError] = useState<string>();

	const preflight = useMutation({
		...gitLabPreflightMutation(),
		onSuccess: (data, { body }) => {
			dispatch({ type: "SET_PREFLIGHT_RESULT", result: data, request: body });
		},
	});

	const handleValidate = () => {
		if (preflight.isPending) {
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
		// Reset any stale mutation state before firing the new request
		preflight.reset();
		preflight.mutate({
			body: {
				personalAccessToken: result.data.personalAccessToken,
				serverUrl: state.serverUrl,
			},
		});
	};

	return (
		<div className="flex flex-col gap-4">
			<Field>
				<FieldLabel htmlFor="gitlab-server-url">GitLab Instance</FieldLabel>
				<Input
					id="gitlab-server-url"
					value={state.serverUrl}
					disabled
					aria-describedby="gitlab-server-url-description"
				/>
				<FieldDescription id="gitlab-server-url-description">
					The GitLab instance Hephaestus reads from, set by your administrator.
				</FieldDescription>
			</Field>

			<Field data-invalid={hasText(tokenError) ? "true" : undefined}>
				<FieldLabel htmlFor="gitlab-pat">Access Token</FieldLabel>
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
							if (e.key === "Enter") {
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
					<a
						href={`${state.serverUrl}/help/user/group/settings/group_access_tokens`}
						target="_blank"
						rel="noopener noreferrer"
					>
						Group Access Token
					</a>{" "}
					with <strong>Owner</strong> role and <code className="text-xs">api</code> scope. Owner
					role is required for webhook registration. Enable token rotation for long-lived setups.
				</FieldDescription>
				{hasText(tokenError) && <FieldError id="gitlab-pat-error">{tokenError}</FieldError>}
			</Field>

			<Button
				type="button"
				onClick={handleValidate}
				disabled={!state.personalAccessToken.trim() || preflight.isPending}
			>
				{preflight.isPending && <Spinner className="mr-2" />}
				Validate Token
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
					<AlertTitle>Validation failed</AlertTitle>
					<AlertDescription>
						{firstNonBlank(state.preflightResult.error) ??
							"The token could not be validated. Check your token and try again."}
					</AlertDescription>
				</Alert>
			)}

			{preflight.isError && isForCurrentToken(state, preflight.variables.body) && (
				<Alert variant="destructive">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Connection error</AlertTitle>
					<AlertDescription>
						Could not reach the GitLab instance. Try again in a moment.
					</AlertDescription>
				</Alert>
			)}
		</div>
	);
}
