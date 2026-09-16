import { useEffect, useRef, useState } from "react";

import type {
	CreateLlmConnectionRequest,
	LlmConnection,
	LlmProbeResult,
	UpdateLlmConnectionRequest,
} from "@/api/types.gen";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
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
import type { FieldErrors, LlmConnectionFormField } from "@/lib/llm-form-validation";
import { defaultProtocolFor, type LlmAuthMode } from "@/lib/llm-provider-type";
import { hasText } from "@/lib/text";

import {
	connectionFieldsValueOf,
	LlmConnectionFields,
	type LlmConnectionFieldsValue,
	validateConnectionFields,
} from "./LlmConnectionFields";

export interface AdminLlmConnectionFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: LlmConnection | null;
	isSubmitting: boolean;
	onCreate: (body: CreateLlmConnectionRequest) => void;
	onUpdate: (id: number, body: UpdateLlmConnectionRequest) => void;
	onProbe: (
		request: {
			apiProtocol: string;
			baseUrl: string;
			apiKey?: string;
			authMode?: LlmAuthMode;
		},
		callbacks: { onSuccess: (result: LlmProbeResult) => void; onError: (message: string) => void },
	) => void;
	onProbeSaved?: (
		id: number,
		callbacks: { onSuccess: (result: LlmProbeResult) => void; onError: (message: string) => void },
	) => void;
	isProbing: boolean;
	onProbed?: (models: string[]) => void;
}

export function AdminLlmConnectionFormDialog({
	open,
	onOpenChange,
	editing,
	...contentProps
}: AdminLlmConnectionFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<AdminLlmConnectionFormDialogContent
					key={editing?.id ?? "new"}
					editing={editing}
					onOpenChange={onOpenChange}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

/** The display name is deliberately not compared: naming a connection after testing it must not
 * discard the result. */
function probeInputsDiffer(a: LlmConnectionFieldsValue, b: LlmConnectionFieldsValue): boolean {
	return (
		a.baseUrl !== b.baseUrl ||
		a.useResponsesApi !== b.useResponsesApi ||
		a.authMode !== b.authMode ||
		a.apiKey !== b.apiKey ||
		a.clearApiKey !== b.clearApiKey
	);
}

type AdminLlmConnectionFormDialogContentProps = Omit<AdminLlmConnectionFormDialogProps, "open">;

function AdminLlmConnectionFormDialogContent({
	onOpenChange,
	editing,
	isSubmitting,
	onCreate,
	onUpdate,
	onProbe,
	onProbeSaved,
	isProbing,
	onProbed,
}: AdminLlmConnectionFormDialogContentProps) {
	const isEdit = editing !== null;
	const [fields, setFields] = useState<LlmConnectionFieldsValue>(() =>
		connectionFieldsValueOf(editing),
	);
	const [probeResult, setProbeResult] = useState<LlmProbeResult | null>(null);
	const [probeError, setProbeError] = useState<string | null>(null);
	const [errors, setErrors] = useState<FieldErrors<LlmConnectionFormField>>({});
	// Invalidates an in-flight probe whose inputs have since changed, so a slow answer about the old
	// endpoint cannot land as if it were about the new one.
	const probeGeneration = useRef(0);
	// A probe answer arriving after the dialog closed must not reach `onProbed`.
	const isMounted = useRef(true);
	useEffect(() => {
		isMounted.current = true;
		return () => {
			isMounted.current = false;
		};
	}, []);

	const apiProtocol = defaultProtocolFor(fields.useResponsesApi);
	const clearProbe = () => {
		probeGeneration.current += 1;
		setProbeResult(null);
		setProbeError(null);
		onProbed?.([]);
	};

	const handleFieldsChange = (next: LlmConnectionFieldsValue) => {
		if (probeInputsDiffer(fields, next)) {
			clearProbe();
		}
		setFields(next);
	};

	const handleTest = () => {
		const generation = probeGeneration.current + 1;
		probeGeneration.current = generation;
		setProbeResult(null);
		setProbeError(null);
		onProbed?.([]);
		const callbacks = {
			onSuccess: (result: LlmProbeResult) => {
				if (probeGeneration.current !== generation || !isMounted.current) {
					return;
				}
				setProbeResult(result);
				if (result.reachable) {
					onProbed?.(result.models);
				}
			},
			onError: (message: string) => {
				if (probeGeneration.current === generation && isMounted.current) {
					setProbeError(message);
				}
			},
		};
		if (editing && !fields.apiKey.trim() && !fields.clearApiKey) {
			onProbeSaved?.(editing.id, callbacks);
			return;
		}
		onProbe(
			{
				apiProtocol,
				baseUrl: fields.baseUrl.trim(),
				apiKey: fields.apiKey.trim() || undefined,
				authMode: fields.authMode,
			},
			callbacks,
		);
	};

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		const found = validateConnectionFields(fields, isEdit);
		setErrors(found);
		if (Object.keys(found).length > 0) {
			return;
		}

		if (editing) {
			const body: UpdateLlmConnectionRequest = { displayName: fields.displayName.trim() };
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
			apiProtocol,
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
						Connect an endpoint that implements an OpenAI API. Models are added and priced after the
						connection is saved.
					</DialogDescription>
				</DialogHeader>

				{/* This form outgrows a 320 px viewport in both directions, so only the body scrolls. */}
				<DialogBody className="space-y-4 py-1">
					<LlmConnectionFields
						value={fields}
						onChange={handleFieldsChange}
						errors={errors}
						isEdit={isEdit}
						hasApiKey={Boolean(editing?.hasApiKey)}
						apiKeyLast4={editing?.apiKeyLast4}
					/>

					{!isEdit && (
						<p className="text-sm text-muted-foreground">
							New connections start inactive. Save and test the connection, add a priced model, then
							activate it from the connections table.
						</p>
					)}

					<div className="space-y-2">
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={isProbing || !fields.baseUrl.trim()}
							onClick={handleTest}
						>
							{isProbing
								? "Testing…"
								: isEdit && !fields.apiKey.trim() && !fields.clearApiKey
									? "Test saved connection"
									: isEdit
										? "Test changes"
										: "Test & fetch models"}
						</Button>
						{/* The outcome of a button the admin just pressed, not a failure: `role="alert"` is
						    assertive and would cut across whatever is being read (SC 4.1.3). */}
						{probeResult?.reachable === true && (
							<Alert variant="success" role="status">
								<AlertDescription>
									Reachable. Found {probeResult.models.length} model
									{probeResult.models.length === 1 ? "" : "s"}.
									{probeResult.models.length > 0 && (
										<div className="mt-1.5 flex flex-wrap gap-1">
											{probeResult.models.slice(0, 12).map((modelId) => (
												<Badge key={modelId} variant="outline" size="xs" className="font-mono">
													{modelId}
												</Badge>
											))}
										</div>
									)}
								</AlertDescription>
							</Alert>
						)}
						{probeResult && !probeResult.reachable && (
							<Alert variant="warning">
								<AlertDescription>
									Discovery unsupported. {probeResult.message ?? "The provider didn't answer."} You
									can still save the connection and enter a model id.
								</AlertDescription>
							</Alert>
						)}
						{hasText(probeError) && (
							<Alert variant="warning">
								<AlertDescription>
									Discovery unsupported. {probeError} You can still save the connection and enter a
									model id.
								</AlertDescription>
							</Alert>
						)}
					</div>
				</DialogBody>

				<DialogFooter>
					<Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
						Cancel
					</Button>
					<Button type="submit" disabled={isSubmitting}>
						{isEdit ? "Save changes" : "Save inactive connection"}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
