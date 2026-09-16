import { useState } from "react";

import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";

import { type WorkspaceDetailsData, workspaceDetailsSchema } from "./schemas";
import { generateSlug } from "./slug-utils";
import { useWizard } from "./wizard-context";

const DETAIL_FIELDS = [
	"displayName",
	"workspaceSlug",
] as const satisfies readonly (keyof WorkspaceDetailsData)[];

export function ConfigureWorkspaceStep() {
	const { state, dispatch } = useWizard();
	const [fieldErrors, setFieldErrors] = useState<
		Partial<Record<keyof WorkspaceDetailsData, string>>
	>({});
	const [touched, setTouched] = useState<Partial<Record<keyof WorkspaceDetailsData, boolean>>>({});

	// Focus is managed by the parent page via headingRef on step change.

	const validate = (overrides: Partial<Record<keyof WorkspaceDetailsData, string>> = {}) => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: overrides.displayName ?? state.displayName,
			workspaceSlug: overrides.workspaceSlug ?? state.workspaceSlug,
		});
		if (result.success) {
			setFieldErrors({});
		} else {
			const errors: Partial<Record<keyof WorkspaceDetailsData, string>> = {};
			for (const issue of result.error.issues) {
				const field = DETAIL_FIELDS.find((candidate) => candidate === issue.path[0]);
				if (field) {
					errors[field] = issue.message;
				}
			}
			setFieldErrors(errors);
		}
	};

	const handleDisplayNameChange = (value: string) => {
		dispatch({ type: "SET_DISPLAY_NAME", value });
		const slug = state.slugManuallyEdited ? state.workspaceSlug : generateSlug(value);
		if (!state.slugManuallyEdited) {
			dispatch({ type: "SET_SLUG", value: slug, manual: false });
		}
		// Only re-validate if user already blurred this field (eager after first blur)
		if (touched.displayName) {
			validate({ displayName: value, workspaceSlug: slug });
		}
	};

	const handleSlugChange = (value: string) => {
		dispatch({ type: "SET_SLUG", value, manual: true });
		if (touched.workspaceSlug) {
			validate({ workspaceSlug: value });
		}
	};

	const handleBlur = (field: keyof WorkspaceDetailsData) => {
		setTouched((prev) => ({ ...prev, [field]: true }));
		validate();
	};

	return (
		<div className="flex flex-col gap-4">
			<Field data-invalid={fieldErrors.displayName && touched.displayName ? "true" : undefined}>
				<FieldLabel htmlFor="workspace-display-name">Display Name</FieldLabel>
				<Input
					id="workspace-display-name"
					placeholder="My Workspace"
					value={state.displayName}
					onChange={(e) => handleDisplayNameChange(e.target.value)}
					onBlur={() => handleBlur("displayName")}
					aria-required="true"
					aria-invalid={!!(fieldErrors.displayName && touched.displayName)}
					aria-describedby={
						fieldErrors.displayName && touched.displayName
							? "workspace-display-name-error"
							: "workspace-display-name-description"
					}
				/>
				<FieldDescription id="workspace-display-name-description">
					The name shown in navigation and headers.
				</FieldDescription>
				{fieldErrors.displayName && touched.displayName && (
					<FieldError id="workspace-display-name-error">{fieldErrors.displayName}</FieldError>
				)}
			</Field>

			<Field data-invalid={fieldErrors.workspaceSlug && touched.workspaceSlug ? "true" : undefined}>
				<FieldLabel htmlFor="workspace-slug">URL Slug</FieldLabel>
				<Input
					id="workspace-slug"
					placeholder="my-workspace"
					aria-required="true"
					value={state.workspaceSlug}
					onChange={(e) => handleSlugChange(e.target.value)}
					onBlur={() => handleBlur("workspaceSlug")}
					aria-invalid={!!(fieldErrors.workspaceSlug && touched.workspaceSlug)}
					aria-describedby={
						fieldErrors.workspaceSlug && touched.workspaceSlug
							? "workspace-slug-error"
							: "workspace-slug-description"
					}
				/>
				<FieldDescription id="workspace-slug-description">
					Used in URLs: /w/<strong>{state.workspaceSlug || "my-workspace"}</strong>
				</FieldDescription>
				{fieldErrors.workspaceSlug && touched.workspaceSlug && (
					<FieldError id="workspace-slug-error">{fieldErrors.workspaceSlug}</FieldError>
				)}
			</Field>

			{/* Summary */}
			<div className="rounded-lg border bg-muted/30 p-3 text-sm">
				<h2
					id="workspace-summary-heading"
					className="mb-1.5 text-xs font-medium tracking-wider text-muted-foreground uppercase"
				>
					Summary
				</h2>
				<dl className="space-y-1.5" aria-labelledby="workspace-summary-heading">
					<SummaryRow label="Provider" value="GitLab" />
					<SummaryRow label="Instance" value={state.serverUrl || "https://gitlab.com"} />
					<SummaryRow label="Group" value={state.selectedGroup?.fullPath ?? "—"} />
					<SummaryRow label="Token owner" value={state.preflightResult?.username ?? "—"} />
				</dl>
			</div>
		</div>
	);
}

function SummaryRow({ label, value }: { label: string; value: string }) {
	return (
		<div className="flex justify-between">
			<dt className="text-muted-foreground">{label}</dt>
			<dd className="ml-4 truncate font-medium">{value}</dd>
		</div>
	);
}
