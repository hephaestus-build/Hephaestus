import { useState } from "react";
import {
	LlmModelCapabilities,
	type LlmModelCapabilitiesProps,
} from "@/components/admin/instance-llm/LlmModelCapabilities";

import type { LlmModel, UpdateLlmModelSharingRequest } from "@/api/types.gen";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	Field,
	FieldDescription,
	FieldLegend,
	FieldSeparator,
	FieldSet,
} from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import type { AdminLlmModelSaveBody } from "@/lib/admin-llm-model-save";
import type { FieldErrors, LlmModelFormField } from "@/lib/llm-form-validation";

import { modelCreateBodyOf, modelPriceBodyOf, modelUpdateBodyOf } from "./llm-model-request";
import {
	LlmModelFields,
	type LlmModelFieldsValue,
	modelFieldsValueOf,
	validateModelFields,
} from "./LlmModelFields";
import { type ModelAccessScope, ModelAccessScopeChoice } from "./ModelAccessScopeChoice";
import type { PriceModeValue } from "./PriceModeEditor";
import { type WorkspaceOption, workspaceFacetOptions } from "./workspace-options";

export interface AdminLlmModelFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: LlmModel | null;
	workspaceOptions: WorkspaceOption[];
	probedModelIds: string[];
	isSubmitting: boolean;
	capabilities?: LlmModelCapabilitiesProps;
	onSave: (body: AdminLlmModelSaveBody) => void;
}

function priceValueOf(model: LlmModel | null): PriceModeValue {
	return {
		pricingMode: model?.currentPrice?.pricingMode ?? "UNPRICED",
		per1mInputUsd: model?.currentPrice?.per1mInputUsd,
		per1mOutputUsd: model?.currentPrice?.per1mOutputUsd,
		per1mCacheReadUsd: model?.currentPrice?.per1mCacheReadUsd,
		per1mCacheWriteUsd: model?.currentPrice?.per1mCacheWriteUsd,
		note: model?.currentPrice?.note,
	};
}

/** Creation includes initial access; a later access change goes through the dedicated dialog, which
 * states its immediate impact. */
export function AdminLlmModelFormDialog({
	open,
	onOpenChange,
	editing,
	...contentProps
}: AdminLlmModelFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<AdminLlmModelFormDialogContent
					key={editing?.id ?? "new"}
					editing={editing}
					onOpenChange={onOpenChange}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type AdminLlmModelFormDialogContentProps = Omit<AdminLlmModelFormDialogProps, "open">;

function AdminLlmModelFormDialogContent({
	onOpenChange,
	editing,
	workspaceOptions,
	probedModelIds,
	isSubmitting,
	capabilities,
	onSave,
}: AdminLlmModelFormDialogContentProps) {
	const isEdit = editing !== null;
	const submitLabel = isEdit ? "Save changes" : "Add model";
	const [fields, setFields] = useState<LlmModelFieldsValue>(() =>
		modelFieldsValueOf(editing, priceValueOf(editing)),
	);
	const [accessScope, setAccessScope] = useState<ModelAccessScope>(
		editing?.visibility === "PUBLIC" ? "ALL" : "SELECTED",
	);
	const [sharedWorkspaceIds, setSharedWorkspaceIds] = useState<number[]>(
		editing?.grantedWorkspaceIds ?? [],
	);
	const [errors, setErrors] = useState<FieldErrors<LlmModelFormField>>({});

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		const found = validateModelFields(fields, isEdit);
		setErrors(found);
		if (Object.keys(found).length > 0) {
			return;
		}

		const metadata = isEdit ? modelUpdateBodyOf(fields) : modelCreateBodyOf(fields);
		const priceBody = modelPriceBodyOf(fields.price);

		if (isEdit) {
			onSave({ metadata, price: priceBody });
			return;
		}
		const sharing: UpdateLlmModelSharingRequest =
			accessScope === "ALL"
				? { visibility: "PUBLIC" }
				: { visibility: "GRANTED", workspaceIds: sharedWorkspaceIds };
		onSave({ metadata, price: priceBody, sharing });
	};

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit model" : "Add model"}</DialogTitle>
					<DialogDescription>
						Name the model and declare how it handles data. Workspaces and developers see the
						declaration as a badge.
					</DialogDescription>
				</DialogHeader>

				{/* This form outgrows a 320 px viewport; only the body scrolls, so the title and
				    "Add model" stay reachable. */}
				<DialogBody className="space-y-4 py-1">
					<LlmModelFields
						audience="instance"
						idPrefix="llm-model"
						isEdit={isEdit}
						wasEnabled={editing?.enabled ?? false}
						savedTier={editing?.dataHandlingTier}
						value={fields}
						onChange={setFields}
						errors={errors}
						upstreamIdSuggestions={probedModelIds}
						limits={editing?.limits}
					/>

					{!isEdit && (
						<FieldSet>
							<FieldLegend variant="label">Initial workspace access</FieldLegend>
							<ModelAccessScopeChoice
								idPrefix="llm-model-share"
								label="Initial workspace access"
								value={accessScope}
								onChange={setAccessScope}
							/>
							{accessScope === "SELECTED" && (
								<Field className="mt-3">
									<FacetMultiSelect
										variant="field"
										id="llm-model-share-workspaces"
										title="Workspaces"
										options={workspaceFacetOptions(workspaceOptions)}
										selected={sharedWorkspaceIds}
										onChange={setSharedWorkspaceIds}
										emptyLabel="No workspaces yet"
									/>
									{sharedWorkspaceIds.length === 0 && (
										<FieldDescription>
											No workspace can use this model yet. This is safe for staging. Manage access
											from the model table when it is ready.
										</FieldDescription>
									)}
								</Field>
							)}
						</FieldSet>
					)}
					{isEdit && capabilities && (
						<>
							<FieldSeparator />
							<LlmModelCapabilities {...capabilities} />
						</>
					)}
				</DialogBody>

				<DialogFooter>
					<Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
						Cancel
					</Button>
					<Button type="submit" disabled={isSubmitting}>
						{isSubmitting && <Spinner aria-hidden />}
						{isSubmitting ? "Saving…" : submitLabel}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
