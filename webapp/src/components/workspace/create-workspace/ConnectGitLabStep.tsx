import { useMutation } from "@tanstack/react-query";
import { CircleCheckIcon, EyeIcon, EyeOffIcon, OctagonXIcon } from "lucide-react";
import { useEffect, useState } from "react";

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
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { firstNonBlank, hasText } from "@/lib/text";

import { type ConnectionFormData, connectionSchema } from "./schemas";
import { useWizard } from "./wizard-context";

export interface GitLabInstanceOption {
	registrationId: string;
	displayName: string;
	baseUrl: string;
}

const CONNECTION_FIELDS = [
	"serverUrl",
	"personalAccessToken",
] as const satisfies readonly (keyof ConnectionFormData)[];

const NO_INSTANCES: readonly GitLabInstanceOption[] = [];

export function ConnectGitLabStep({
	instances = NO_INSTANCES,
}: {
	instances?: readonly GitLabInstanceOption[];
}) {
	const { state, dispatch } = useWizard();
	// Only instances with a known base URL can be offered as a pick; a blank one falls back to the
	// read-only configured field.
	const selectableInstances = instances.filter((i) => Boolean(i.baseUrl));
	const multipleInstances = selectableInstances.length > 1;

	// Keep state.serverUrl pinned to a real instance when a picker is shown, so preflight + creation use
	// the selected instance even before the user touches the dropdown.
	useEffect(() => {
		if (!multipleInstances) {
			return;
		}
		const matches = selectableInstances.some((i) => i.baseUrl === state.serverUrl);
		const [firstInstance] = selectableInstances;
		if (!matches && firstInstance) {
			dispatch({ type: "SET_SERVER_URL", value: firstInstance.baseUrl });
		}
	}, [multipleInstances, selectableInstances, state.serverUrl, dispatch]);
	const [showToken, setShowToken] = useState(false);
	const [fieldErrors, setFieldErrors] = useState<Partial<Record<keyof ConnectionFormData, string>>>(
		{},
	);

	const preflight = useMutation({
		...gitLabPreflightMutation(),
		onSuccess: (data) => {
			dispatch({ type: "SET_PREFLIGHT_RESULT", result: data });
		},
	});

	const handleValidate = () => {
		if (preflight.isPending) {
			return;
		}
		const result = connectionSchema.safeParse({
			serverUrl: state.serverUrl,
			personalAccessToken: state.personalAccessToken,
		});
		if (!result.success) {
			const errors: Partial<Record<keyof ConnectionFormData, string>> = {};
			for (const issue of result.error.issues) {
				const field = CONNECTION_FIELDS.find((candidate) => candidate === issue.path[0]);
				if (field) {
					errors[field] = issue.message;
				}
			}
			setFieldErrors(errors);
			return;
		}
		setFieldErrors({});
		// Persist trimmed values back to state so downstream steps use normalized data
		dispatch({ type: "SET_SERVER_URL", value: result.data.serverUrl });
		dispatch({ type: "SET_PAT", value: result.data.personalAccessToken });
		// Reset any stale mutation state before firing the new request
		preflight.reset();
		preflight.mutate({
			body: {
				personalAccessToken: result.data.personalAccessToken,
				serverUrl: result.data.serverUrl || undefined,
			},
		});
	};

	const settingsBaseUrl = state.serverUrl || "https://gitlab.com";

	return (
		<div className="flex flex-col gap-4">
			<Field data-invalid={hasText(fieldErrors.serverUrl) ? "true" : undefined}>
				<FieldLabel id="gitlab-server-url-label" htmlFor="gitlab-server-url">
					GitLab Instance
				</FieldLabel>
				{multipleInstances ? (
					<Select
						items={selectableInstances.map((instance) => ({
							value: instance.baseUrl,
							label: `${instance.displayName} (${instance.baseUrl})`,
						}))}
						value={firstNonBlank(state.serverUrl, selectableInstances[0]?.baseUrl) ?? ""}
						onValueChange={(value) => dispatch({ type: "SET_SERVER_URL", value: value ?? "" })}
					>
						<SelectTrigger id="gitlab-server-url">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby="gitlab-server-url-label">
							{selectableInstances.map((instance) => (
								<SelectItem key={instance.registrationId} value={instance.baseUrl}>
									{instance.displayName} ({instance.baseUrl})
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				) : (
					<Input
						id="gitlab-server-url"
						value={state.serverUrl || "https://gitlab.com"}
						disabled
						aria-describedby="gitlab-server-url-description"
					/>
				)}
				<FieldDescription id="gitlab-server-url-description">
					{multipleInstances
						? "Pick the GitLab instance hosting the group you'll monitor."
						: "Configured by your administrator."}
				</FieldDescription>
			</Field>

			<Field data-invalid={hasText(fieldErrors.personalAccessToken) ? "true" : undefined}>
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
						aria-invalid={hasText(fieldErrors.personalAccessToken)}
						aria-describedby={
							hasText(fieldErrors.personalAccessToken)
								? "gitlab-pat-error"
								: "gitlab-pat-description"
						}
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
						href={`${settingsBaseUrl}/help/user/group/settings/group_access_tokens`}
						target="_blank"
						rel="noopener noreferrer"
					>
						Group Access Token
					</a>{" "}
					with <strong>Owner</strong> role and <code className="text-xs">api</code> scope. Owner
					role is required for webhook registration. Enable token rotation for long-lived setups.
				</FieldDescription>
				{hasText(fieldErrors.personalAccessToken) && (
					<FieldError id="gitlab-pat-error">{fieldErrors.personalAccessToken}</FieldError>
				)}
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

			{preflight.isError && !state.preflightResult && (
				<Alert variant="destructive">
					<OctagonXIcon aria-hidden="true" />
					<AlertTitle>Connection error</AlertTitle>
					<AlertDescription>
						Could not reach the GitLab instance. Check the URL and try again.
					</AlertDescription>
				</Alert>
			)}
		</div>
	);
}
