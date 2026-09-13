import { useState } from "react";

import type {
	CreateLlmModelRequest,
	LlmModel,
	UpdateLlmModelPriceRequest,
	UpdateLlmModelRequest,
	UpdateLlmModelSharingRequest,
} from "@/api/types.gen";
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
import { Field, FieldDescription, FieldLegend, FieldSet } from "@/components/ui/field";
import type { AdminLlmModelSaveBody } from "@/lib/admin-llm-model-save";
import type { FieldErrors, LlmModelFormField } from "@/lib/llm-form-validation";

import {
	dataHandlingBodyOf,
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
	onSave,
}: AdminLlmModelFormDialogContentProps) {
	const isEdit = editing !== null;
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
		if (Object.keys(found).length > 0) return;

		const { price } = fields;
		const metadataShared = {
			displayName: fields.displayName.trim(),
			contextWindow: fields.contextWindow.trim() ? Number(fields.contextWindow) : undefined,
			maxOutputTokens: fields.maxOutputTokens.trim() ? Number(fields.maxOutputTokens) : undefined,
			supportsReasoning: fields.supportsReasoning,
			...dataHandlingBodyOf(fields),
			enabled: fields.enabled,
		};
		const metadata: CreateLlmModelRequest | UpdateLlmModelRequest = isEdit
			? metadataShared
			: { ...metadataShared, upstreamModelId: fields.upstreamModelId.trim() };

		const priceBody: UpdateLlmModelPriceRequest = {
			pricingMode: price.pricingMode,
			per1mInputUsd: price.pricingMode === "PRICED" ? price.per1mInputUsd : undefined,
			per1mOutputUsd: price.pricingMode === "PRICED" ? price.per1mOutputUsd : undefined,
			per1mCacheReadUsd: price.pricingMode === "PRICED" ? price.per1mCacheReadUsd : undefined,
			per1mCacheWriteUsd: price.pricingMode === "PRICED" ? price.per1mCacheWriteUsd : undefined,
			note: price.pricingMode === "NO_CHARGE" ? price.note?.trim() : undefined,
		};

		const sharingBody: UpdateLlmModelSharingRequest | undefined = isEdit
			? undefined
			: accessScope === "ALL"
				? { visibility: "PUBLIC" }
				: { visibility: "GRANTED", workspaceIds: sharedWorkspaceIds };

		onSave({ metadata, price: priceBody, ...(sharingBody ? { sharing: sharingBody } : {}) });
	};

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit model" : "Add model"}</DialogTitle>
					<DialogDescription>
						Name the model and declare how it handles data; workspaces and developers see the
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
											No workspace can use this model yet. This is safe for staging; manage access
											from the model table when it is ready.
										</FieldDescription>
									)}
								</Field>
							)}
						</FieldSet>
					)}
				</DialogBody>

				<DialogFooter>
					<Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
						Cancel
					</Button>
					<Button type="submit" disabled={isSubmitting}>
						{isEdit ? "Save changes" : "Add model"}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
