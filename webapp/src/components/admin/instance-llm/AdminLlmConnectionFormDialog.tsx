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
import { listsNoModels, UNLISTED_MODELS } from "@/lib/llm-api-protocol-labels";
import type { FieldErrors, LlmConnectionFormField } from "@/lib/llm-form-validation";
import type { LlmApiProtocol, LlmAuthMode } from "@/lib/llm-provider-type";
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
			apiProtocol: LlmApiProtocol;
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
		a.apiProtocol !== b.apiProtocol ||
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

	const { apiProtocol } = fields;
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
			const body: UpdateLlmConnectionRequest = {
				displayName: fields.displayName.trim(),
			};
			if (fields.connectionPlatform) {
				body.connectionPlatform = fields.connectionPlatform;
			} else if (editing.connectionPlatform) {
				body.clearConnectionPlatform = true;
			}
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
			connectionPlatform: fields.connectionPlatform,
			apiKey: fields.apiKey.trim() || undefined,
			enabled: false,
		});
	};

	// A failed test that is not only a missing model list is a fault, in the server's words.
	let failure = probeError;
	if (probeResult !== null && !probeResult.reachable && !listsNoModels(apiProtocol, probeResult)) {
		failure = probeResult.message ?? "The provider did not answer.";
	}

	let testLabel = "Test and fetch models";
	if (isProbing) {
		testLabel = "Testing…";
	} else if (isEdit && !fields.apiKey.trim() && !fields.clearApiKey) {
		testLabel = "Test saved connection";
	} else if (isEdit) {
		testLabel = "Test changes";
	}

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit connection" : "Add connection"}</DialogTitle>
					<DialogDescription>
						Connect an OpenAI-compatible or Cohere-compatible endpoint. Add and price its models
						next.
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

					<div>
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={isProbing || !fields.baseUrl.trim()}
							onClick={handleTest}
						>
							{testLabel}
						</Button>
						{/* Mounted empty, so a result is announced when it arrives (ARIA22). Polite: it answers
						    the button the admin just pressed, and must not cut across what is being read. */}
						<div role="status" className="not-empty:mt-2">
							<ProbeFound apiProtocol={apiProtocol} probeResult={probeResult} />
						</div>
						{hasText(failure) && (
							<div className="mt-2">
								<Alert variant="warning">
									<AlertDescription>
										We could not fetch the model list. {failure} You can still save the connection
										and enter a model ID.
									</AlertDescription>
								</Alert>
							</div>
						)}
					</div>
				</DialogBody>

				<DialogFooter>
					<Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
						Cancel
					</Button>
					<Button type="submit" disabled={isSubmitting}>
						{isEdit ? "Save changes" : "Add connection"}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}

interface ProbeFoundProps {
	apiProtocol: LlmApiProtocol;
	probeResult: LlmProbeResult | null;
}

/**
 * What a test that is no fault found: the models, or that a precompute endpoint lists none. The
 * status region around it announces it, so it takes no alert role of its own.
 */
function ProbeFound({ apiProtocol, probeResult }: ProbeFoundProps) {
	if (probeResult?.reachable === true) {
		return (
			<Alert variant="success" role="none">
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
		);
	}
	if (probeResult !== null && listsNoModels(apiProtocol, probeResult)) {
		return (
			<Alert role="none">
				<AlertDescription>{UNLISTED_MODELS}</AlertDescription>
			</Alert>
		);
	}
	return null;
}
