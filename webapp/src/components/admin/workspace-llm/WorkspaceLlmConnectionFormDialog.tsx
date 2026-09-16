import { AlertTriangle } from "lucide-react";
import { useId, useState } from "react";

import type {
	CreateWorkspaceLlmConnectionRequest,
	UpdateWorkspaceLlmConnectionRequest,
	WorkspaceLlmConnection,
} from "@/api/types.gen";
import {
	connectionFieldsValueOf,
	LlmConnectionFields,
	type LlmConnectionFieldsValue,
	validateConnectionFields,
} from "@/components/admin/instance-llm/LlmConnectionFields";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import type { FieldErrors, LlmConnectionFormField } from "@/lib/llm-form-validation";
import { defaultProtocolFor } from "@/lib/llm-provider-type";

export interface WorkspaceLlmConnectionFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: WorkspaceLlmConnection | null;
	isSubmitting: boolean;
	onCreate: (body: CreateWorkspaceLlmConnectionRequest) => void;
	onUpdate: (id: number, body: UpdateWorkspaceLlmConnectionRequest) => void;
}

export function WorkspaceLlmConnectionFormDialog({
	open,
	onOpenChange,
	editing,
	...contentProps
}: WorkspaceLlmConnectionFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<WorkspaceLlmConnectionFormDialogContent
					key={editing?.id ?? "new"}
					editing={editing}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type WorkspaceLlmConnectionFormDialogContentProps = Omit<
	WorkspaceLlmConnectionFormDialogProps,
	"open" | "onOpenChange"
>;

function WorkspaceLlmConnectionFormDialogContent({
	editing,
	isSubmitting,
	onCreate,
	onUpdate,
}: WorkspaceLlmConnectionFormDialogContentProps) {
	const isEdit = editing !== null;
	const [fields, setFields] = useState<LlmConnectionFieldsValue>(() =>
		connectionFieldsValueOf(editing),
	);
	const [enabled, setEnabled] = useState(editing?.enabled ?? false);
	const [errors, setErrors] = useState<FieldErrors<LlmConnectionFormField>>({});
	const enabledId = useId();

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		const found = validateConnectionFields(fields, isEdit);
		setErrors(found);
		if (Object.keys(found).length > 0) {
			return;
		}

		if (editing) {
			const body: UpdateWorkspaceLlmConnectionRequest = {
				displayName: fields.displayName.trim(),
				enabled,
			};
			if (fields.apiKey.trim()) {
				body.apiKey = fields.apiKey.trim();
			}
			if (fields.clearApiKey) {
				body.clearApiKey = true;
			}
			onUpdate(editing.id, body);
			return;
		}

		onCreate({
			displayName: fields.displayName.trim(),
			baseUrl: fields.baseUrl.trim(),
			apiProtocol: defaultProtocolFor(fields.useResponsesApi),
			authMode: fields.authMode,
			apiKey: fields.apiKey.trim() || undefined,
			enabled: false,
		});
	};

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit connection" : "Add connection"}</DialogTitle>
					<DialogDescription>
						Connect an endpoint that implements an OpenAI API. Add and price its models next.
					</DialogDescription>
				</DialogHeader>

				{/* This form outgrows a 320 px viewport, so the body scrolls and the submit stays on screen. */}
				<DialogBody className="space-y-4 py-1">
					<LlmConnectionFields
						value={fields}
						onChange={setFields}
						errors={errors}
						isEdit={isEdit}
						hasApiKey={Boolean(editing?.hasApiKey)}
						apiKeyLast4={editing?.apiKeyLast4}
					/>

					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={enabledId}>Active</FieldLabel>
							<FieldDescription>
								{isEdit
									? "Turn off to stop new requests using this connection."
									: "Starts inactive. Test it, add a priced model, then activate both."}
							</FieldDescription>
						</FieldContent>
						<Switch
							id={enabledId}
							checked={enabled}
							disabled={!isEdit}
							onCheckedChange={setEnabled}
						/>
					</Field>
					{editing?.enabled === true && !enabled && (
						<Alert variant="warning">
							<AlertTriangle aria-hidden />
							<AlertTitle>All workspace models will stop immediately</AlertTitle>
							<AlertDescription>
								Practice reviews and the mentor can’t run until you reactivate this provider or pick
								another model.
							</AlertDescription>
						</Alert>
					)}
				</DialogBody>
				<DialogFooter>
					<DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
					<Button type="submit" disabled={isSubmitting}>
						{isEdit ? "Save changes" : "Connect provider"}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
