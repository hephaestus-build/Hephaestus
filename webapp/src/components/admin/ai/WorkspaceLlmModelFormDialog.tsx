import { useState } from "react";

import type {
	CreateWorkspaceLlmModelRequest,
	UpdateWorkspaceLlmModelRequest,
	WorkspaceLlmModel,
} from "@/api/types.gen";
import {
	LlmModelFields,
	type LlmModelFieldsValue,
	modelFieldsValueOf,
	validateModelFields,
} from "@/components/admin/llm/LlmModelFields";
import type { PriceModeValue } from "@/components/admin/llm/PriceModeEditor";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import type { FieldErrors, LlmModelFormField } from "@/lib/llm-form-validation";

export interface WorkspaceLlmModelFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: WorkspaceLlmModel | null;
	isSubmitting: boolean;
	onCreate: (body: CreateWorkspaceLlmModelRequest) => void;
	onUpdate: (id: number, body: UpdateWorkspaceLlmModelRequest) => void;
}

function priceValueOf(model: WorkspaceLlmModel | null): PriceModeValue {
	return {
		pricingMode: model?.pricingMode ?? "UNPRICED",
		per1mInputUsd: model?.per1mInputUsd,
		per1mOutputUsd: model?.per1mOutputUsd,
		per1mCacheReadUsd: model?.per1mCacheReadUsd,
		per1mCacheWriteUsd: model?.per1mCacheWriteUsd,
		note: model?.priceNote,
	};
}

/** Price is set inline: the workspace scope has no separate price endpoint, unlike the instance
 * catalog. */
export function WorkspaceLlmModelFormDialog({
	open,
	onOpenChange,
	editing,
	...contentProps
}: WorkspaceLlmModelFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<WorkspaceLlmModelFormDialogContent
					key={editing?.id ?? "new"}
					editing={editing}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type WorkspaceLlmModelFormDialogContentProps = Omit<
	WorkspaceLlmModelFormDialogProps,
	"open" | "onOpenChange"
>;

function WorkspaceLlmModelFormDialogContent({
	editing,
	isSubmitting,
	onCreate,
	onUpdate,
}: WorkspaceLlmModelFormDialogContentProps) {
	const isEdit = editing !== null;
	const [fields, setFields] = useState<LlmModelFieldsValue>(() =>
		modelFieldsValueOf(editing, priceValueOf(editing)),
	);
	const [errors, setErrors] = useState<FieldErrors<LlmModelFormField>>({});

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		const found = validateModelFields(fields, isEdit);
		setErrors(found);
		if (Object.keys(found).length > 0) return;

		const { price } = fields;
		const shared = {
			displayName: fields.displayName.trim(),
			contextWindow: fields.contextWindow.trim() ? Number(fields.contextWindow) : undefined,
			maxOutputTokens: fields.maxOutputTokens.trim() ? Number(fields.maxOutputTokens) : undefined,
			supportsReasoning: fields.supportsReasoning,
			processingLocation: fields.processingLocation,
			enabled: isEdit ? fields.enabled : false,
			pricingMode: price.pricingMode,
			per1mInputUsd: price.pricingMode === "PRICED" ? price.per1mInputUsd : undefined,
			per1mOutputUsd: price.pricingMode === "PRICED" ? price.per1mOutputUsd : undefined,
			per1mCacheReadUsd: price.pricingMode === "PRICED" ? price.per1mCacheReadUsd : undefined,
			per1mCacheWriteUsd: price.pricingMode === "PRICED" ? price.per1mCacheWriteUsd : undefined,
			priceNote: price.pricingMode === "NO_CHARGE" ? price.note?.trim() : undefined,
		};

		if (isEdit) {
			onUpdate(editing.id, shared satisfies UpdateWorkspaceLlmModelRequest);
			return;
		}
		onCreate({
			...shared,
			upstreamModelId: fields.upstreamModelId.trim(),
		} satisfies CreateWorkspaceLlmModelRequest);
	};

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit model" : "Add model"}</DialogTitle>
				</DialogHeader>

				{/* This form outgrows a 320 px viewport; without an internal scroll region the popup hangs
				    off both edges and neither the title nor the submit button can be reached. */}
				<DialogBody className="space-y-4 py-1">
					<LlmModelFields
						audience="workspace"
						idPrefix="wm"
						isEdit={isEdit}
						wasEnabled={editing?.enabled ?? false}
						value={fields}
						onChange={setFields}
						errors={errors}
					/>
				</DialogBody>

				<DialogFooter>
					<DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
					<Button type="submit" disabled={isSubmitting}>
						{isEdit ? "Save changes" : "Add inactive model"}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
