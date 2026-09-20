import { useId } from "react";

import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import {
	type FieldErrors,
	type LlmConnectionFormField,
	validateLlmConnectionForm,
} from "@/lib/llm-form-validation";
import {
	authModeDefaultFor,
	baseUrlDefaultFor,
	type LlmAuthMode,
	type OpenAiConnectionIdentity,
	PROVIDER_PRESET_LABELS,
	PROVIDER_PRESET_ORDER,
	PROVIDER_PRESET_SELECT_ITEMS,
	type ProviderPreset,
	presetForConnection,
	usesResponsesApi,
} from "@/lib/llm-provider-type";
import { hasText } from "@/lib/text";

export interface LlmConnectionFieldsValue {
	displayName: string;
	baseUrl: string;
	preset: ProviderPreset;
	useResponsesApi: boolean;
	authMode: LlmAuthMode;
	/** Always blank on open: a stored key is never read back to the browser. */
	apiKey: string;
	clearApiKey: boolean;
}

type EditedConnection = OpenAiConnectionIdentity & {
	displayName: string;
	authMode?: LlmAuthMode;
};

export function connectionFieldsValueOf(
	connection: EditedConnection | null,
): LlmConnectionFieldsValue {
	return {
		displayName: connection?.displayName ?? "",
		baseUrl: connection?.baseUrl ?? baseUrlDefaultFor("OPENAI"),
		preset: connection ? presetForConnection(connection) : "OPENAI",
		useResponsesApi: connection ? usesResponsesApi(connection.apiProtocol) : false,
		authMode: connection?.authMode ?? "BEARER",
		apiKey: "",
		clearApiKey: false,
	};
}

export function validateConnectionFields(
	value: LlmConnectionFieldsValue,
	isEdit: boolean,
): FieldErrors<LlmConnectionFormField> {
	return validateLlmConnectionForm({
		displayName: value.displayName,
		// Immutable once created, so an edit neither sends nor validates it.
		baseUrl: isEdit ? undefined : value.baseUrl,
	});
}

export interface LlmConnectionFieldsProps {
	value: LlmConnectionFieldsValue;
	onChange: (value: LlmConnectionFieldsValue) => void;
	errors: FieldErrors<LlmConnectionFormField>;
	isEdit: boolean;
	hasApiKey: boolean;
	apiKeyLast4?: string;
}

export function LlmConnectionFields({
	value,
	onChange,
	errors,
	isEdit,
	hasApiKey,
	apiKeyLast4,
}: LlmConnectionFieldsProps) {
	const update = (patch: Partial<LlmConnectionFieldsValue>) => onChange({ ...value, ...patch });

	const displayNameId = useId();
	const presetId = useId();
	const presetLabelId = useId();
	const responsesApiId = useId();
	const baseUrlId = useId();
	const authModeId = useId();
	const authModeLabelId = useId();
	const apiKeyId = useId();
	const clearApiKeyId = useId();
	const displayNameErrorId = useId();
	const baseUrlErrorId = useId();

	const applyPreset = (next: ProviderPreset) => {
		const baseUrlWasTypedByHand =
			Boolean(value.baseUrl) && value.baseUrl !== baseUrlDefaultFor(value.preset);
		update({
			preset: next,
			authMode: authModeDefaultFor(next),
			...(baseUrlWasTypedByHand ? {} : { baseUrl: baseUrlDefaultFor(next) }),
		});
	};

	return (
		<>
			<Field data-invalid={Boolean(errors.displayName)}>
				<FieldLabel htmlFor={displayNameId}>Display name</FieldLabel>
				<Input
					id={displayNameId}
					value={value.displayName}
					onChange={(event) => update({ displayName: event.target.value })}
					placeholder="e.g. Production OpenAI"
					// Inert under `noValidate`, but it is what announces the field as required (SC 3.3.2).
					required
					aria-invalid={Boolean(errors.displayName)}
					aria-describedby={hasText(errors.displayName) ? displayNameErrorId : undefined}
				/>
				{hasText(errors.displayName) && (
					<FieldError id={displayNameErrorId}>{errors.displayName}</FieldError>
				)}
			</Field>

			{!isEdit && (
				<FieldGroup className="gap-3">
					<Field>
						<FieldLabel id={presetLabelId} htmlFor={presetId}>
							Endpoint preset
						</FieldLabel>
						<Select
							items={PROVIDER_PRESET_SELECT_ITEMS}
							value={value.preset}
							onValueChange={(next) => {
								if (hasText(next)) {
									applyPreset(next);
								}
							}}
						>
							<SelectTrigger id={presetId} className="w-full">
								<SelectValue />
							</SelectTrigger>
							<SelectContent aria-labelledby={presetLabelId}>
								{PROVIDER_PRESET_ORDER.map((item) => (
									<SelectItem key={item} value={item}>
										{PROVIDER_PRESET_LABELS[item]}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
						{value.preset === "AZURE_OPENAI_V1" && (
							<FieldDescription>
								Replace RESOURCE below with your Azure resource name. The v1 API does not need an
								api-version parameter.
							</FieldDescription>
						)}
					</Field>

					<Field orientation="horizontal">
						<Checkbox
							id={responsesApiId}
							checked={value.useResponsesApi}
							onCheckedChange={(checked) => update({ useResponsesApi: checked })}
						/>
						<FieldContent>
							<FieldLabel htmlFor={responsesApiId} className="font-normal">
								Use the Responses API instead of Chat Completions
							</FieldLabel>
						</FieldContent>
					</Field>
				</FieldGroup>
			)}

			<Field data-invalid={Boolean(errors.baseUrl)}>
				<FieldLabel htmlFor={baseUrlId}>Base URL</FieldLabel>
				<Input
					id={baseUrlId}
					type="url"
					value={value.baseUrl}
					onChange={(event) => update({ baseUrl: event.target.value })}
					disabled={isEdit}
					placeholder="https://api.openai.com/v1"
					required={!isEdit}
					autoComplete="off"
					aria-invalid={Boolean(errors.baseUrl)}
					aria-describedby={hasText(errors.baseUrl) ? baseUrlErrorId : undefined}
				/>
				{isEdit && (
					<FieldDescription>
						Endpoint, API shape and authentication can’t change. Add a connection instead.
					</FieldDescription>
				)}
				{hasText(errors.baseUrl) && <FieldError id={baseUrlErrorId}>{errors.baseUrl}</FieldError>}
			</Field>

			{!isEdit && value.preset === "OTHER" && (
				<Field>
					<FieldLabel id={authModeLabelId} htmlFor={authModeId}>
						Authentication
					</FieldLabel>
					<Select
						items={[
							{ value: "BEARER", label: "Bearer token" },
							{ value: "API_KEY", label: "api-key header" },
						]}
						value={value.authMode}
						onValueChange={(next) => {
							if (hasText(next)) {
								update({ authMode: next });
							}
						}}
					>
						<SelectTrigger id={authModeId} className="w-full">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby={authModeLabelId}>
							<SelectItem value="BEARER">Bearer token</SelectItem>
							<SelectItem value="API_KEY">api-key header</SelectItem>
						</SelectContent>
					</Select>
				</Field>
			)}

			<FieldGroup className="gap-3">
				<Field>
					<FieldLabel htmlFor={apiKeyId}>API key</FieldLabel>
					<Input
						id={apiKeyId}
						type="password"
						value={value.apiKey}
						onChange={(event) => update({ apiKey: event.target.value })}
						disabled={value.clearApiKey}
						placeholder={
							hasApiKey ? `Configured · ends in ····${apiKeyLast4 ?? "····"}` : "Enter API key"
						}
						autoComplete="off"
					/>
					<FieldDescription>
						{hasApiKey ? "Leave blank to keep the current key." : "Stored encrypted."}
					</FieldDescription>
				</Field>

				{hasApiKey && (
					<Field orientation="horizontal">
						<Checkbox
							id={clearApiKeyId}
							checked={value.clearApiKey}
							onCheckedChange={(checked) =>
								update({ clearApiKey: checked, ...(checked && { apiKey: "" }) })
							}
						/>
						<FieldContent>
							<FieldLabel htmlFor={clearApiKeyId} className="font-normal">
								Remove stored API key
							</FieldLabel>
						</FieldContent>
					</Field>
				)}
			</FieldGroup>
		</>
	);
}
