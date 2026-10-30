import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { BrainCircuit, Plus } from "lucide-react";
import { type ReactNode, useState } from "react";
import { isPracticeScriptApi } from "@/lib/llm-api-protocol-labels";
import { toolChoiceTestState } from "@/lib/llm-tool-choice-test";
import { toast } from "@/lib/toast";

import {
	adminCreateLlmConnectionMutation,
	adminCreateLlmModelMutation,
	adminDeleteLlmConnectionMutation,
	adminDeleteLlmModelMutation,
	adminGetLlmSettingsOptions,
	adminGetLlmSettingsQueryKey,
	adminListLlmConnectionsOptions,
	adminListLlmConnectionsQueryKey,
	adminListLlmModelsOptions,
	adminListLlmModelsQueryKey,
	adminListWorkspacesOptions,
	adminProbeLlmConnectionDraftMutation,
	adminProbeLlmConnectionMutation,
	adminProbeLlmModelCapabilitiesMutation,
	adminUpdateLlmConnectionMutation,
	adminUpdateLlmModelMutation,
	adminUpdateLlmModelPriceMutation,
	adminUpdateLlmModelSharingMutation,
	adminUpdateLlmSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateLlmConnectionRequest,
	LlmConnection,
	LlmModel,
	UpdateInstanceLlmSettingsRequest,
	UpdateLlmConnectionRequest,
	UpdateLlmModelSharingRequest,
} from "@/api/types.gen";
import { AdminLlmConnectionFormDialog } from "@/components/admin/instance-llm/AdminLlmConnectionFormDialog";
import { AdminLlmConnectionsTable } from "@/components/admin/instance-llm/AdminLlmConnectionsTable";
import { AdminLlmModelAccessDialog } from "@/components/admin/instance-llm/AdminLlmModelAccessDialog";
import { AdminLlmModelFormDialog } from "@/components/admin/instance-llm/AdminLlmModelFormDialog";
import { AdminLlmModelsSection } from "@/components/admin/instance-llm/AdminLlmModelsSection";
import { InstanceLlmSettingsCard } from "@/components/admin/instance-llm/InstanceLlmSettingsCard";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button } from "@/components/ui/button";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import {
	type AdminLlmModelSaveBody,
	AdminLlmModelSaveError,
	saveAdminLlmModelSafely,
} from "@/lib/admin-llm-model-save";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/models")({
	head: instanceAdminHead("AI models"),
	component: AdminLlmPage,
});

const CONNECTION_WRITE_MUTATION_KEY = ["adminWriteLlmConnection"];
const MODEL_WRITE_MUTATION_KEY = ["adminWriteLlmModel"];

/**
 * The one dialog the page shows; they are modal, so at most one is open. Every opening and every
 * close is a new object, the closed page included, so a write that finishes after its dialog was
 * dismissed, or after another one came and went, finds a different object and leaves what is open now
 * alone. A request for a fresh sign-in takes the place of the dialog it came from rather than stacking
 * a second focus trap on it.
 */
type RouteDialog =
	| { kind: "idle" }
	| { kind: "addConnection" }
	| { kind: "editConnection"; connection: LlmConnection }
	| { kind: "addModel"; connectionId: number }
	| { kind: "editModel"; model: LlmModel }
	| { kind: "access"; model: LlmModel }
	| { kind: "challenge"; challenge: StepUpChallenge };
type ConnectionDialog = Extract<RouteDialog, { kind: "addConnection" | "editConnection" }>;
type ModelDialog = Extract<RouteDialog, { kind: "addModel" | "editModel" }>;
type AccessDialog = Extract<RouteDialog, { kind: "access" }>;

/** Closes the dialog only if it is still the one the result belongs to. */
function closedIf(origin: RouteDialog | null) {
	return function closeOrigin(current: RouteDialog): RouteDialog {
		return current === origin ? { kind: "idle" } : current;
	};
}

/** Asks for a fresh sign-in only if what sent the refused request is still what is open. */
function challengedIf(origin: RouteDialog, challenge: StepUpChallenge) {
	return function askOrigin(current: RouteDialog): RouteDialog {
		return current === origin ? { kind: "challenge", challenge } : current;
	};
}

const STEP_UP_NOTICE = "Changes were not saved. Confirm access and try again.";

/**
 * Every refusal is reported here, the one that asks for a fresh sign-in included: the ask itself
 * appears only if what sent the request is still open, and the change was not saved either way.
 */
function reportConnectionError(error: unknown, title: string) {
	if (stepUpChallengeOf(error) === undefined) {
		toast.error(title, { description: problemDetailOf(error) });
	} else {
		toast.error(STEP_UP_NOTICE);
	}
}

/**
 * One model save, from first request to last. A new model has no id yet, so its save is filed under
 * the connection it is added to.
 */
interface ModelSave {
	path: { id: number } | { connectionId: number };
	connectionId: number;
	editing: LlmModel | null;
	body: AdminLlmModelSaveBody;
}

const creatingOnConnection = (variables: unknown) =>
	pathNumber(variables, "id") === undefined ? pathNumber(variables, "connectionId") : undefined;

function AdminLlmPage() {
	const queryClient = useQueryClient();

	const [selectedConnectionId, setSelectedConnectionId] = useState<number | null>(null);
	const [dialog, setDialog] = useState<RouteDialog>(() => ({ kind: "idle" }));
	const connectionDialog: ConnectionDialog | null =
		dialog.kind === "addConnection" || dialog.kind === "editConnection" ? dialog : null;
	const editingConnection = dialog.kind === "editConnection" ? dialog.connection : null;
	const modelDialog: ModelDialog | null =
		dialog.kind === "addModel" || dialog.kind === "editModel" ? dialog : null;
	const accessDialog = dialog.kind === "access" ? dialog : null;
	const challengeDialog = dialog.kind === "challenge" ? dialog : null;
	const challenge = challengeDialog?.challenge;
	const [probedModels, setProbedModels] = useState<{
		connectionId: number | null;
		models: string[];
	} | null>(null);

	const connectionsQuery = useQuery(adminListLlmConnectionsOptions());
	const connections = connectionsQuery.data ?? [];
	const selectedConnection =
		connections.find((c) => c.id === selectedConnectionId) ?? connections[0];

	const capabilityProbe = useMutation({
		...adminProbeLlmModelCapabilitiesMutation(),
		onSuccess: async () =>
			queryClient.invalidateQueries({ queryKey: adminListLlmModelsQueryKey() }),
	});
	const modelsQuery = useQuery(adminListLlmModelsOptions());
	const allModels = modelsQuery.data ?? [];
	const modelCounts: Record<number, number> = {};
	for (const model of allModels) {
		modelCounts[model.connectionId] = (modelCounts[model.connectionId] ?? 0) + 1;
	}
	const modelsForSelectedConnection = allModels.filter(
		(m) => m.connectionId === selectedConnection?.id,
	);

	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const workspaceOptions = (workspacesQuery.data ?? []).map((w) => ({
		id: w.id,
		displayName: w.displayName,
		workspaceSlug: w.workspaceSlug,
	}));

	const settingsQuery = useQuery(adminGetLlmSettingsOptions());

	const invalidateConnections = async () =>
		queryClient.invalidateQueries({ queryKey: adminListLlmConnectionsQueryKey() });
	const invalidateModels = async () =>
		queryClient.invalidateQueries({ queryKey: adminListLlmModelsQueryKey() });

	const confirmAccess = useConfirmAccess(challenge !== undefined);

	const askAgainIf = (origin: RouteDialog, error: unknown) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp) {
			setDialog(challengedIf(origin, stepUp));
		}
	};

	const createConnection = useMutation({
		...adminCreateLlmConnectionMutation(),
		onSuccess: (created) => {
			void invalidateConnections();
			setSelectedConnectionId(created.id);
			// Models fetched in the add form belong to the connection it created; another form's stay.
			setProbedModels((current) =>
				current?.connectionId === null
					? { connectionId: created.id, models: current.models }
					: current,
			);
			toast.success("Connection added");
		},
		onError: (error) => reportConnectionError(error, "We could not add the connection"),
	});

	const updateConnection = useMutation({
		...filedUnder(CONNECTION_WRITE_MUTATION_KEY, adminUpdateLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateConnections();
			toast.success("Connection updated");
		},
		onError: (error) => reportConnectionError(error, "We could not update the connection"),
	});

	// A write outlives the dialog it came from, which may be dismissed meanwhile, so its result is
	// applied only to that dialog: a later one is neither closed nor sent through a fresh sign-in.
	const saveConnectionForm = async (origin: RouteDialog, request: Promise<unknown>) => {
		try {
			await request;
			setDialog(closedIf(origin));
		} catch (error) {
			askAgainIf(origin, error);
		}
	};
	// A row action starts on the closed page. Any dialog opened before it settles, even one closed again
	// since, leaves a different closed page behind, so a late refusal asks nothing over it.
	const runConnectionAction = async (request: Promise<unknown>) => {
		const origin = dialog;
		try {
			await request;
		} catch (error) {
			askAgainIf(origin, error);
		}
	};

	const deleteConnection = useMutation({
		...filedUnder(CONNECTION_WRITE_MUTATION_KEY, adminDeleteLlmConnectionMutation()),
		onSuccess: (_data, variables) => {
			void invalidateConnections();
			if (variables.path.id === selectedConnectionId) {
				setSelectedConnectionId(null);
			}
			toast.success("Connection deleted");
		},
		onError: (error) => reportConnectionError(error, "We could not delete the connection"),
	});

	const mutatingConnectionIds = usePendingMutationIds(CONNECTION_WRITE_MUTATION_KEY, (variables) =>
		pathNumber(variables, "id"),
	);

	const probeDraft = useMutation({ ...adminProbeLlmConnectionDraftMutation() });
	const probeSaved = useMutation({ ...adminProbeLlmConnectionMutation() });

	const createModel = useMutation({ ...adminCreateLlmModelMutation() });
	const updateModel = useMutation({ ...adminUpdateLlmModelMutation() });
	const updatePrice = useMutation({ ...adminUpdateLlmModelPriceMutation() });
	const updateSharing = useMutation({
		...filedUnder(MODEL_WRITE_MUTATION_KEY, adminUpdateLlmModelSharingMutation()),
	});
	// The steps of one save run in order; as one mutation, its pending state covers the gaps between them.
	const saveModel = useMutation({
		mutationKey: MODEL_WRITE_MUTATION_KEY,
		mutationFn: async ({ connectionId, editing, body }: ModelSave) =>
			saveAdminLlmModelSafely({
				connectionId,
				editing,
				body,
				operations: {
					create: async (parentId, metadata) =>
						createModel.mutateAsync({ path: { connectionId: parentId }, body: metadata }),
					updateMetadata: async (id, metadata) =>
						updateModel.mutateAsync({ path: { id }, body: metadata }),
					updatePrice: async (id, price) => updatePrice.mutateAsync({ path: { id }, body: price }),
					updateSharing: async (id, sharing) =>
						updateSharing.mutateAsync({ path: { id }, body: sharing }),
				},
			}),
	});
	const deleteModel = useMutation({
		...filedUnder(MODEL_WRITE_MUTATION_KEY, adminDeleteLlmModelMutation()),
		onSuccess: () => {
			void invalidateModels();
			toast.success("Model deleted");
		},
		onError: (error) =>
			toast.error("We could not delete the model", { description: problemDetailOf(error) }),
	});

	const mutatingModelIds = usePendingMutationIds(MODEL_WRITE_MUTATION_KEY, (variables) =>
		pathNumber(variables, "id"),
	);
	const addingModelConnectionIds = usePendingMutationIds(
		MODEL_WRITE_MUTATION_KEY,
		creatingOnConnection,
	);

	const updateSettings = useMutation({
		...adminUpdateLlmSettingsMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminGetLlmSettingsQueryKey() });
			toast.success("Settings saved");
		},
		onError: (error) =>
			toast.error("We could not save settings", { description: problemDetailOf(error) }),
	});

	// A form is busy only while its own subject saves: another row's write is not its business.
	const connectionFormSubmitting =
		editingConnection === null
			? createConnection.isPending
			: mutatingConnectionIds.has(editingConnection.id);
	const modelFormSubmitting =
		modelDialog?.kind === "editModel"
			? mutatingModelIds.has(modelDialog.model.id)
			: modelDialog !== null && addingModelConnectionIds.has(modelDialog.connectionId);

	// Awaited here rather than in call-level callbacks, which TanStack runs only for the latest call.
	const saveAccess = async (origin: AccessDialog, body: UpdateLlmModelSharingRequest) => {
		try {
			await updateSharing.mutateAsync({ path: { id: origin.model.id }, body });
			void invalidateModels();
			setDialog(closedIf(origin));
			toast.success("Workspace access updated");
		} catch (error) {
			toast.error("We could not update workspace access", { description: problemDetailOf(error) });
		}
	};

	// The save outlives its form, which may be dismissed meanwhile; its result closes only that form.
	const handleSaveModel = async (saved: ModelDialog, body: AdminLlmModelSaveBody) => {
		const editing = saved.kind === "editModel" ? saved.model : null;
		const connectionId = saved.kind === "editModel" ? saved.model.connectionId : saved.connectionId;
		try {
			await saveModel.mutateAsync({
				path: editing ? { id: editing.id } : { connectionId },
				connectionId,
				editing,
				body,
			});
			void invalidateModels();
			setDialog(closedIf(saved));
			toast.success(editing ? "Model updated" : "Model added");
		} catch (error) {
			void invalidateModels();
			if (error instanceof AdminLlmModelSaveError && error.modelId != null) {
				toast.error("Model saved inactive, but setup is incomplete", {
					description: "Review the model and save again before activating it.",
				});
			} else {
				toast.error("We could not save the model", { description: problemDetailOf(error) });
			}
		}
	};

	let modelsSection: ReactNode = null;
	if (selectedConnection) {
		if (modelsQuery.isError) {
			modelsSection = (
				<QueryErrorAlert
					error={modelsQuery.error}
					title="We could not load models"
					onRetry={() => {
						void modelsQuery.refetch();
					}}
				/>
			);
		} else {
			modelsSection = (
				<AdminLlmModelsSection
					connection={selectedConnection}
					workspaceOptions={workspaceOptions}
					models={modelsForSelectedConnection}
					isLoading={modelsQuery.isLoading}
					mutatingIds={mutatingModelIds}
					addDisabled={addingModelConnectionIds.has(selectedConnection.id)}
					onAdd={() => setDialog({ kind: "addModel", connectionId: selectedConnection.id })}
					onEdit={(model) => setDialog({ kind: "editModel", model })}
					onManageAccess={(model) => setDialog({ kind: "access", model })}
					onDelete={(model) => {
						deleteModel.mutate({ path: { id: model.id } });
					}}
				/>
			);
		}
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<BrainCircuit />}
				title="AI models"
				description="Connect OpenAI-compatible endpoints and share models with workspaces."
				actions={
					<Button
						disabled={!connectionsQuery.isSuccess || createConnection.isPending}
						onClick={() => {
							setProbedModels({ connectionId: null, models: [] });
							setDialog({ kind: "addConnection" });
						}}
					>
						<Plus className="size-4" aria-hidden />
						Add connection
					</Button>
				}
			/>

			<AdminLlmConnectionsTable
				connections={connections}
				modelCounts={modelCounts}
				modelCountsAvailable={modelsQuery.isSuccess}
				isLoading={connectionsQuery.isLoading}
				isError={connectionsQuery.isError}
				error={connectionsQuery.error}
				onRetry={() => {
					void connectionsQuery.refetch();
				}}
				mutatingIds={mutatingConnectionIds}
				addDisabled={createConnection.isPending}
				selectedId={selectedConnection?.id ?? null}
				onSelect={(connection) => {
					setSelectedConnectionId(connection.id);
				}}
				onEdit={(connection) => {
					setProbedModels({ connectionId: connection.id, models: [] });
					setDialog({ kind: "editConnection", connection });
				}}
				onToggleEnabled={(connection, enabled) => {
					void runConnectionAction(
						updateConnection.mutateAsync({ path: { id: connection.id }, body: { enabled } }),
					);
				}}
				onDelete={(connection) => {
					void runConnectionAction(deleteConnection.mutateAsync({ path: { id: connection.id } }));
				}}
				onAdd={() => {
					setProbedModels({ connectionId: null, models: [] });
					setDialog({ kind: "addConnection" });
				}}
			/>

			{modelsSection}

			{settingsQuery.isError ? (
				<QueryErrorAlert
					error={settingsQuery.error}
					title="We could not load AI settings"
					onRetry={() => {
						void settingsQuery.refetch();
					}}
				/>
			) : (
				<InstanceLlmSettingsCard
					settings={settingsQuery.data}
					isLoading={settingsQuery.isLoading}
					isSubmitting={updateSettings.isPending}
					onSave={(body: UpdateInstanceLlmSettingsRequest) => updateSettings.mutate({ body })}
				/>
			)}

			<AdminLlmConnectionFormDialog
				open={connectionDialog !== null}
				onOpenChange={(open) => {
					if (!open) {
						setDialog(closedIf(connectionDialog));
						setProbedModels(null);
					}
				}}
				editing={editingConnection}
				isSubmitting={connectionFormSubmitting}
				onCreate={(body: CreateLlmConnectionRequest) => {
					void saveConnectionForm(dialog, createConnection.mutateAsync({ body }));
				}}
				onUpdate={(id, body: UpdateLlmConnectionRequest) => {
					void saveConnectionForm(dialog, updateConnection.mutateAsync({ path: { id }, body }));
				}}
				isProbing={probeDraft.isPending || probeSaved.isPending}
				onProbe={(request, callbacks) => {
					probeDraft.mutate(
						{ body: request },
						{
							onSuccess: callbacks.onSuccess,
							onError: (error) =>
								callbacks.onError(problemDetailOf(error, "The provider did not answer.")),
						},
					);
				}}
				onProbeSaved={(id, callbacks) => {
					probeSaved.mutate(
						{ path: { id } },
						{
							onSuccess: callbacks.onSuccess,
							onError: (error) =>
								callbacks.onError(problemDetailOf(error, "The provider did not answer.")),
						},
					);
				}}
				onProbed={(models) =>
					setProbedModels({ connectionId: editingConnection?.id ?? null, models })
				}
			/>

			<AdminLlmModelFormDialog
				open={modelDialog !== null}
				onOpenChange={(open) => {
					if (!open) {
						capabilityProbe.reset();
						setDialog(closedIf(modelDialog));
					}
				}}
				editing={modelDialog?.kind === "editModel" ? modelDialog.model : null}
				capabilities={
					modelDialog?.kind === "editModel" &&
					connections.some(
						(connection) =>
							connection.id === modelDialog.model.connectionId &&
							!isPracticeScriptApi(connection.apiProtocol) &&
							connection.apiProtocol !== "anthropic-messages",
					)
						? {
								supported: allModels.find((model) => model.id === modelDialog.model.id)
									?.forcedToolChoice,
								test: toolChoiceTestState(modelDialog.model.id, capabilityProbe),
								onProbe: () => capabilityProbe.mutate({ path: { id: modelDialog.model.id } }),
							}
						: undefined
				}
				workspaceOptions={workspaceOptions}
				probedModelIds={
					probedModels && selectedConnection && probedModels.connectionId === selectedConnection.id
						? probedModels.models
						: []
				}
				isSubmitting={modelFormSubmitting}
				onSave={(body) => {
					if (modelDialog !== null) {
						void handleSaveModel(modelDialog, body);
					}
				}}
			/>

			<AdminLlmModelAccessDialog
				open={accessDialog !== null}
				onOpenChange={(open) => {
					if (!open) {
						setDialog(closedIf(accessDialog));
					}
				}}
				model={accessDialog?.model ?? null}
				workspaceOptions={workspaceOptions}
				isLoadingWorkspaces={workspacesQuery.isLoading}
				workspacesError={workspacesQuery.error}
				onRetryWorkspaces={() => {
					void workspacesQuery.refetch();
				}}
				isSubmitting={accessDialog !== null && mutatingModelIds.has(accessDialog.model.id)}
				onSave={(body) => {
					if (accessDialog !== null) {
						void saveAccess(accessDialog, body);
					}
				}}
			/>

			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setDialog(closedIf(challengeDialog));
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</PageLayout>
	);
}
