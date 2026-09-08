import { useState } from "react";

import type {
	CreateLoginProviderRequest,
	LoginProviderView,
	UpdateLoginProviderRequest,
} from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";

type ProviderType = CreateLoginProviderRequest["type"];

const PROVIDER_TYPE_ITEMS: { value: ProviderType; label: string }[] = [
	{ value: "GITHUB", label: "GitHub" },
	{ value: "GITLAB", label: "GitLab / self-hosted GitLab" },
	{ value: "SLACK", label: "Slack / Sign in with Slack" },
	{ value: "OUTLINE", label: "Outline (link-only)" },
	{ value: "OIDC", label: "Organization / OpenID Connect" },
];

function scopesPlaceholder(type: ProviderType): string {
	if (type === "SLACK" || type === "OIDC") return "openid profile email";
	if (type === "OUTLINE") return "read";
	return "Defaulted by provider type if blank";
}

function toProviderType(value: string | null | undefined): ProviderType {
	return PROVIDER_TYPE_ITEMS.find((item) => item.value === value)?.value ?? "GITLAB";
}

interface LoginProviderFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: LoginProviderView | null;
	isSubmitting: boolean;
	onCreate: (body: CreateLoginProviderRequest) => void;
	onUpdate: (registrationId: string, body: UpdateLoginProviderRequest) => void;
}

export function LoginProviderFormDialog({
	open,
	onOpenChange,
	editing,
	isSubmitting,
	onCreate,
	onUpdate,
}: LoginProviderFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="sm:max-w-lg">
				{/* The popup unmounts on close and the key remounts on a change of record, so the fields
				    always start from `editing` and never need re-seeding in an effect. */}
				<ProviderForm
					key={editing?.registrationId ?? "new"}
					editing={editing}
					isSubmitting={isSubmitting}
					onCreate={onCreate}
					onUpdate={onUpdate}
					onCancel={() => onOpenChange(false)}
				/>
			</DialogContent>
		</Dialog>
	);
}

interface ProviderFormProps {
	editing: LoginProviderView | null;
	isSubmitting: boolean;
	onCreate: (body: CreateLoginProviderRequest) => void;
	onUpdate: (registrationId: string, body: UpdateLoginProviderRequest) => void;
	onCancel: () => void;
}

function ProviderForm({ editing, isSubmitting, onCreate, onUpdate, onCancel }: ProviderFormProps) {
	const isEdit = editing !== null;
	const [registrationId, setRegistrationId] = useState(editing?.registrationId ?? "");
	const [type, setType] = useState<ProviderType>(toProviderType(editing?.type));
	const [displayName, setDisplayName] = useState(editing?.displayName ?? "");
	const [baseUrl, setBaseUrl] = useState(editing?.baseUrl ?? "");
	const [clientId, setClientId] = useState("");
	const [clientSecret, setClientSecret] = useState("");
	const [scopes, setScopes] = useState(editing?.scopes ?? "");
	const [errors, setErrors] = useState<{ registrationId?: string; baseUrl?: string }>({});

	const needsBaseUrl = type === "GITLAB" || type === "OUTLINE" || type === "OIDC";
	const isSlack = type === "SLACK";
	const isOutline = type === "OUTLINE";
	const isOidc = type === "OIDC";

	const redirectUri =
		editing?.redirectUri ??
		`${window.location.origin}/api/login/oauth2/code/${registrationId.trim() || "<registration-id>"}`;

	const REGISTRATION_ID_PATTERN = /^[a-z][a-z0-9-]{1,62}$/;
	const validate = (): boolean => {
		const next: { registrationId?: string; baseUrl?: string } = {};
		if (!isEdit && !REGISTRATION_ID_PATTERN.test(registrationId.trim())) {
			next.registrationId = "Lowercase letters, digits and hyphens; must start with a letter.";
		}
		if (needsBaseUrl && (!isEdit || baseUrl.trim())) {
			const value = baseUrl.trim();
			if (!isEdit && !value) {
				next.baseUrl = isOidc ? "An issuer URL is required." : "An instance base URL is required.";
			} else if (value && !value.startsWith("https://")) {
				next.baseUrl = "Must be an HTTPS URL.";
			}
		}
		setErrors(next);
		return Object.keys(next).length === 0;
	};

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (isSubmitting) return;
		if (!validate()) {
			return;
		}
		if (isEdit) {
			const body: UpdateLoginProviderRequest = {
				displayName: displayName.trim() || undefined,
				baseUrl: needsBaseUrl && !isOidc ? baseUrl.trim() || undefined : undefined,
				clientId: clientId.trim() || undefined,
				clientSecret: clientSecret.trim() || undefined,
				scopes: scopes.trim() || undefined,
			};
			onUpdate(editing.registrationId, body);
			return;
		}
		const body: CreateLoginProviderRequest = {
			registrationId: registrationId.trim(),
			type,
			displayName: displayName.trim() || undefined,
			baseUrl: needsBaseUrl ? baseUrl.trim() || undefined : undefined,
			clientId: clientId.trim(),
			clientSecret: clientSecret.trim(),
			scopes: scopes.trim() || undefined,
		};
		onCreate(body);
	};

	return (
		<form onSubmit={handleSubmit} className="space-y-4">
			<DialogHeader>
				<DialogTitle>{isEdit ? "Edit login provider" : "Add login provider"}</DialogTitle>
				<DialogDescription>
					Configure sign-in and account-link providers. Slack and Outline are link-only: they appear
					in Settings for account linking, not on the public sign-in page.
				</DialogDescription>
			</DialogHeader>

			<Field data-invalid={errors.registrationId ? "true" : undefined}>
				<FieldLabel htmlFor="lp-registration-id">Registration ID</FieldLabel>
				<Input
					id="lp-registration-id"
					value={registrationId}
					onChange={(e) => setRegistrationId(e.target.value)}
					placeholder="gitlab-acme"
					disabled={isEdit}
					required={!isEdit}
					aria-invalid={errors.registrationId ? "true" : undefined}
					aria-describedby="lp-registration-id-description"
					autoComplete="off"
				/>
				<FieldDescription id="lp-registration-id-description">
					Stable id used in the OAuth callback path. Lowercase letters, digits, hyphens. Immutable
					once created.
				</FieldDescription>
				{errors.registrationId && <FieldError>{errors.registrationId}</FieldError>}
			</Field>

			<Field>
				<FieldLabel id="lp-type-label" htmlFor="lp-type">
					Provider type
				</FieldLabel>
				<Select
					items={PROVIDER_TYPE_ITEMS}
					value={type}
					onValueChange={(v) => setType(toProviderType(v))}
					disabled={isEdit}
				>
					<SelectTrigger id="lp-type">
						<SelectValue />
					</SelectTrigger>
					<SelectContent aria-labelledby="lp-type-label">
						{PROVIDER_TYPE_ITEMS.map((item) => (
							<SelectItem key={item.value} value={item.value}>
								{item.label}
							</SelectItem>
						))}
					</SelectContent>
				</Select>
				{isSlack && (
					<FieldDescription>
						Use the same Slack app client ID and secret. Add this provider's redirect URI to the
						Slack app redirect URLs.
					</FieldDescription>
				)}
				{isOutline && (
					<FieldDescription>
						Outline is <strong>link-only</strong>: users connect it from Settings so their documents
						are attributed to them — nobody signs in to Hephaestus with it. Create an OAuth app in
						Outline under <strong>Settings → Applications</strong> and register this redirect URI:{" "}
						<code className="break-all">{redirectUri}</code>
					</FieldDescription>
				)}
				{isOidc && (
					<FieldDescription>
						Use an OpenID Connect client approved by your instance operator. Register this callback
						URI with your identity provider: <code className="break-all">{redirectUri}</code>.
						Existing users should connect this identity from Settings rather than creating a
						separate account.
					</FieldDescription>
				)}
			</Field>

			<Field>
				<FieldLabel htmlFor="lp-display-name">Display name</FieldLabel>
				<Input
					id="lp-display-name"
					value={displayName}
					onChange={(e) => setDisplayName(e.target.value)}
					placeholder="Defaults to the registration ID"
				/>
			</Field>

			{needsBaseUrl && (
				<Field data-invalid={errors.baseUrl ? "true" : undefined}>
					<FieldLabel htmlFor="lp-base-url">
						{isOidc ? "Issuer URL" : "Instance base URL"}
					</FieldLabel>
					<Input
						id="lp-base-url"
						type="url"
						value={baseUrl}
						onChange={(e) => setBaseUrl(e.target.value)}
						placeholder={
							isOidc
								? "https://identity.example.com/realms/team"
								: isOutline
									? "https://outline.example.com"
									: "https://gitlab.example.com"
						}
						readOnly={isEdit && isOidc}
						required={!isEdit}
						aria-invalid={errors.baseUrl ? "true" : undefined}
						aria-describedby="lp-base-url-description"
					/>
					<FieldDescription id="lp-base-url-description">
						{isOidc
							? "Copy the exact HTTPS issuer, including its realm path and any trailing slash. It must be approved by your instance operator and cannot change after creation."
							: "HTTPS only. GitHub and Slack are always at a fixed host, so this field applies to self-hosted GitLab and Outline instances."}
					</FieldDescription>
					{errors.baseUrl && <FieldError>{errors.baseUrl}</FieldError>}
				</Field>
			)}

			<Field>
				<FieldLabel htmlFor="lp-client-id">Client ID</FieldLabel>
				<Input
					id="lp-client-id"
					value={clientId}
					onChange={(e) => setClientId(e.target.value)}
					placeholder={isEdit ? "Leave blank to keep current" : ""}
					required={!isEdit}
					autoComplete="off"
				/>
			</Field>

			<Field>
				<FieldLabel htmlFor="lp-client-secret">Client secret</FieldLabel>
				<Input
					id="lp-client-secret"
					type="password"
					value={clientSecret}
					onChange={(e) => setClientSecret(e.target.value)}
					placeholder={isEdit ? "Leave blank to keep current" : ""}
					required={!isEdit}
					autoComplete="off"
					aria-describedby="lp-client-secret-description"
				/>
				<FieldDescription id="lp-client-secret-description">
					Sealed at rest; never displayed after saving.
				</FieldDescription>
			</Field>

			<Field>
				<FieldLabel htmlFor="lp-scopes">Scopes</FieldLabel>
				<Input
					id="lp-scopes"
					value={scopes}
					onChange={(e) => setScopes(e.target.value)}
					placeholder={scopesPlaceholder(type)}
				/>
			</Field>

			<DialogFooter>
				<Button type="button" variant="outline" onClick={onCancel}>
					Cancel
				</Button>
				<Button type="submit" disabled={isSubmitting}>
					{isSubmitting && <Spinner />}
					{isSubmitting
						? isEdit
							? "Saving…"
							: "Adding…"
						: isEdit
							? "Save changes"
							: "Add provider"}
				</Button>
			</DialogFooter>
		</form>
	);
}
