import { type ReactNode, useState } from "react";

import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { hasText } from "@/lib/text";

import { type WorkspaceDetailsData, workspaceDetailsSchema } from "./schemas";
import { generateSlug } from "./slug-utils";
import { useWizard, type WizardState } from "./wizard-context";

const DETAIL_FIELDS = [
	"displayName",
	"workspaceSlug",
] as const satisfies readonly (keyof WorkspaceDetailsData)[];

/** How the summary states the project choice. */
function projectSummary(scope: WizardState["projectScope"], selected: number): string {
	if (scope === "ALL") {
		return "All projects";
	}
	return selected === 1 ? "1 selected project" : `${selected} selected projects`;
}

export interface ConfigureWorkspaceStepProps {
	/**
	 * The `id` of the form that Enter in the name or slug field submits. The project search is a form
	 * of its own, so this form sits outside the fields rather than around them.
	 */
	formId?: string;
	/** The choice of the group's projects to monitor, which reads the group's projects itself. */
	projects: ReactNode;
	/**
	 * Every field shows its problem, as after a refused "Create workspace". Otherwise a field shows
	 * its problem only after the reader leaves it, never while they type.
	 */
	showErrors?: boolean;
}

/** The first problem with each field, in the order the schema checks them. */
function detailErrors(
	details: WorkspaceDetailsData,
): Partial<Record<keyof WorkspaceDetailsData, string>> {
	const result = workspaceDetailsSchema.safeParse(details);
	const errors: Partial<Record<keyof WorkspaceDetailsData, string>> = {};
	for (const issue of result.error?.issues ?? []) {
		const field = DETAIL_FIELDS.find((candidate) => candidate === issue.path[0]);
		if (field && errors[field] === undefined) {
			errors[field] = issue.message;
		}
	}
	return errors;
}

export function ConfigureWorkspaceStep({
	formId,
	projects,
	showErrors = false,
}: ConfigureWorkspaceStepProps) {
	const { state, dispatch } = useWizard();
	const [touched, setTouched] = useState<Partial<Record<keyof WorkspaceDetailsData, boolean>>>({});

	// Focus is managed by the parent page via headingRef on step change.

	const errors = detailErrors({
		displayName: state.displayName,
		workspaceSlug: state.workspaceSlug,
	});
	const displayNameError =
		showErrors || touched.displayName === true ? errors.displayName : undefined;
	const slugError = showErrors || touched.workspaceSlug === true ? errors.workspaceSlug : undefined;

	const handleDisplayNameChange = (value: string) => {
		dispatch({ type: "SET_DISPLAY_NAME", value });
		if (!state.slugManuallyEdited) {
			dispatch({ type: "SET_SLUG", value: generateSlug(value), manual: false });
		}
	};

	const handleBlur = (field: keyof WorkspaceDetailsData) => {
		setTouched((prev) => ({ ...prev, [field]: true }));
	};

	return (
		<div className="flex flex-col gap-4">
			<Field data-invalid={hasText(displayNameError) ? "true" : undefined}>
				<FieldLabel htmlFor="workspace-display-name">Display name</FieldLabel>
				<Input
					id="workspace-display-name"
					form={formId}
					placeholder="My workspace"
					value={state.displayName}
					onChange={(e) => handleDisplayNameChange(e.target.value)}
					onBlur={() => handleBlur("displayName")}
					aria-required="true"
					aria-invalid={hasText(displayNameError)}
					aria-describedby={
						hasText(displayNameError)
							? "workspace-display-name-description workspace-display-name-error"
							: "workspace-display-name-description"
					}
				/>
				<FieldDescription id="workspace-display-name-description">
					The name shown in navigation and headers.
				</FieldDescription>
				{hasText(displayNameError) && (
					<FieldError id="workspace-display-name-error">{displayNameError}</FieldError>
				)}
			</Field>

			<Field data-invalid={hasText(slugError) ? "true" : undefined}>
				<FieldLabel htmlFor="workspace-slug">URL slug</FieldLabel>
				<Input
					id="workspace-slug"
					form={formId}
					placeholder="my-workspace"
					aria-required="true"
					value={state.workspaceSlug}
					onChange={(e) => dispatch({ type: "SET_SLUG", value: e.target.value, manual: true })}
					onBlur={() => handleBlur("workspaceSlug")}
					aria-invalid={hasText(slugError)}
					aria-describedby={
						hasText(slugError)
							? "workspace-slug-description workspace-slug-error"
							: "workspace-slug-description"
					}
				/>
				<FieldDescription id="workspace-slug-description">
					{hasText(state.workspaceSlug) ? (
						<>
							Used in URLs: /w/<strong>{state.workspaceSlug}</strong>
						</>
					) : (
						"Used in URLs, such as /w/my-workspace."
					)}
				</FieldDescription>
				{hasText(slugError) && <FieldError id="workspace-slug-error">{slugError}</FieldError>}
			</Field>

			{projects}

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
					<SummaryRow label="Instance" value={state.serverUrl} />
					<SummaryRow label="Group" value={state.selectedGroup?.fullPath ?? "—"} />
					<SummaryRow
						label="Projects"
						value={projectSummary(state.projectScope, state.selectedProjectIds.length)}
					/>
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
