import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { BrainCircuit, Plus } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

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
} from "@/api/types.gen";
import { AdminLlmConnectionFormDialog } from "@/components/admin/llm/AdminLlmConnectionFormDialog";
import { AdminLlmConnectionsTable } from "@/components/admin/llm/AdminLlmConnectionsTable";
import { AdminLlmModelAccessDialog } from "@/components/admin/llm/AdminLlmModelAccessDialog";
import { AdminLlmModelFormDialog } from "@/components/admin/llm/AdminLlmModelFormDialog";
import { AdminLlmModelsSection } from "@/components/admin/llm/AdminLlmModelsSection";
import { InstanceLlmSettingsCard } from "@/components/admin/llm/InstanceLlmSettingsCard";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
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

function AdminLlmPage() {
	const queryClient = useQueryClient();

	const [selectedConnectionId, setSelectedConnectionId] = useState<number | null>(null);
	const [connectionDialogOpen, setConnectionDialogOpen] = useState(false);
	const [editingConnection, setEditingConnection] = useState<LlmConnection | null>(null);
	const [probedModels, setProbedModels] = useState<{
		connectionId: number | null;
		models: string[];
	} | null>(null);

	const [modelDialogOpen, setModelDialogOpen] = useState(false);
	const [editingModel, setEditingModel] = useState<LlmModel | null>(null);
	const [accessModel, setAccessModel] = useState<LlmModel | null>(null);

	const connectionsQuery = useQuery(adminListLlmConnectionsOptions());
	const connections = connectionsQuery.data ?? [];
	const selectedConnection =
		connections.find((c) => c.id === selectedConnectionId) ?? connections[0];

	const modelsQuery = useQuery(adminListLlmModelsOptions());
	const allModels = modelsQuery.data ?? [];
	const modelCounts = allModels.reduce<Record<number, number>>((acc, model) => {
		acc[model.connectionId] = (acc[model.connectionId] ?? 0) + 1;
		return acc;
	}, {});
	const modelsForSelectedConnection = selectedConnection
		? allModels.filter((m) => m.connectionId === selectedConnection.id)
		: [];

	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const workspaceOptions = (workspacesQuery.data ?? []).map((w) => ({
		id: w.id,
		displayName: w.displayName,
		workspaceSlug: w.workspaceSlug,
	}));

	const settingsQuery = useQuery(adminGetLlmSettingsOptions());

	const invalidateConnections = () =>
		queryClient.invalidateQueries({ queryKey: adminListLlmConnectionsQueryKey() });
	const invalidateModels = () =>
		queryClient.invalidateQueries({ queryKey: adminListLlmModelsQueryKey() });

	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>(undefined);
	const confirmAccess = useConfirmAccess(challenge !== undefined);

	/**
	 * A refusal that asks for a fresh sign-in is not a failure the operator can read and act on, so
	 * it opens the ask instead of a toast — and it replaces the connection form rather than stacking
	 * a second focus trap on top of it.
	 */
	const reportConnectionError = (error: unknown, title: string) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp) {
			setConnectionDialogOpen(false);
			setChallenge(stepUp);
			return;
		}
		toast.error(title, { description: problemDetailOf(error) });
	};

	const createConnection = useMutation({
		...adminCreateLlmConnectionMutation(),
		onSuccess: (created) => {
			void invalidateConnections();
			setConnectionDialogOpen(false);
			setSelectedConnectionId(created.id);
			setProbedModels((current) =>
				current?.connectionId === null
					? { connectionId: created.id, models: current.models }
					: null,
			);
			toast.success("Connection added");
		},
		onError: (error) => reportConnectionError(error, "Couldn't add the connection"),
	});

	const updateConnection = useMutation({
		...filedUnder(CONNECTION_WRITE_MUTATION_KEY, adminUpdateLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateConnections();
			setConnectionDialogOpen(false);
			toast.success("Connection updated");
		},
		onError: (error) => reportConnectionError(error, "Couldn't update the connection"),
	});

	const deleteConnection = useMutation({
		...filedUnder(CONNECTION_WRITE_MUTATION_KEY, adminDeleteLlmConnectionMutation()),
		onSuccess: (_data, variables) => {
			void invalidateConnections();
			if (variables.path.id === selectedConnectionId) setSelectedConnectionId(null);
			toast.success("Connection deleted");
		},
		onError: (error) => reportConnectionError(error, "Couldn't delete the connection"),
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
	const deleteModel = useMutation({
		...filedUnder(MODEL_WRITE_MUTATION_KEY, adminDeleteLlmModelMutation()),
		onSuccess: () => {
			void invalidateModels();
			toast.success("Model deleted");
		},
		onError: (error) =>
			toast.error("Couldn't delete the model", { description: problemDetailOf(error) }),
	});

	const mutatingModelIds = usePendingMutationIds(MODEL_WRITE_MUTATION_KEY, (variables) =>
		pathNumber(variables, "id"),
	);

	const updateSettings = useMutation({
		...adminUpdateLlmSettingsMutation(),
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminGetLlmSettingsQueryKey() });
			toast.success("Settings saved");
		},
		onError: (error) =>
			toast.error("Couldn't save settings", { description: problemDetailOf(error) }),
	});

	const isModelSaving =
		createModel.isPending ||
		updateModel.isPending ||
		updatePrice.isPending ||
		updateSharing.isPending;

	const handleSaveModel = async (body: AdminLlmModelSaveBody) => {
		if (!selectedConnection) return;
		try {
			await saveAdminLlmModelSafely({
				connectionId: selectedConnection.id,
				editing: editingModel,
				body,
				operations: {
					create: (connectionId, metadata) =>
						createModel.mutateAsync({ path: { connectionId }, body: metadata }),
					updateMetadata: (id, metadata) =>
						updateModel.mutateAsync({ path: { id }, body: metadata }),
					updatePrice: (id, price) => updatePrice.mutateAsync({ path: { id }, body: price }),
					updateSharing: (id, sharing) =>
						updateSharing.mutateAsync({ path: { id }, body: sharing }),
				},
			});
			void invalidateModels();
			setModelDialogOpen(false);
			toast.success(editingModel ? "Model updated" : "Model added");
		} catch (error) {
			void invalidateModels();
			if (error instanceof AdminLlmModelSaveError && error.modelId != null) {
				toast.error("Model saved inactive, but setup is incomplete", {
					description: "Review the model and save again before activating it.",
				});
			} else {
				toast.error("Couldn't save the model", { description: problemDetailOf(error) });
			}
		}
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<BrainCircuit />}
				title="AI models"
				description="Connect OpenAI-compatible endpoints and share models with workspaces."
				actions={
					<Button
						disabled={!connectionsQuery.isSuccess}
						onClick={() => {
							setEditingConnection(null);
							setProbedModels({ connectionId: null, models: [] });
							setConnectionDialogOpen(true);
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
				onRetry={() => void connectionsQuery.refetch()}
				mutatingIds={mutatingConnectionIds}
				selectedId={selectedConnection?.id ?? null}
				onSelect={(connection) => {
					setSelectedConnectionId(connection.id);
				}}
				onEdit={(connection) => {
					setEditingConnection(connection);
					setProbedModels({ connectionId: connection.id, models: [] });
					setConnectionDialogOpen(true);
				}}
				onToggleEnabled={(connection, enabled) => {
					updateConnection.mutate({ path: { id: connection.id }, body: { enabled } });
				}}
				onDelete={(connection) => {
					deleteConnection.mutate({ path: { id: connection.id } });
				}}
				onAdd={() => {
					setEditingConnection(null);
					setProbedModels({ connectionId: null, models: [] });
					setConnectionDialogOpen(true);
				}}
			/>

			{selectedConnection &&
				(modelsQuery.isError ? (
					<QueryErrorAlert
						error={modelsQuery.error}
						title="Could not load models"
						onRetry={() => void modelsQuery.refetch()}
					/>
				) : modelsQuery.isLoading ? (
					<div
						className="flex h-32 items-center justify-center"
						role="status"
						aria-label="Loading models"
					>
						<Spinner className="size-6" />
					</div>
				) : (
					<AdminLlmModelsSection
						connectionDisplayName={selectedConnection.displayName}
						connectionEnabled={selectedConnection.enabled}
						workspaceOptions={workspaceOptions}
						models={modelsForSelectedConnection}
						mutatingIds={mutatingModelIds}
						onAdd={() => {
							setEditingModel(null);
							setModelDialogOpen(true);
						}}
						onEdit={(model) => {
							setEditingModel(model);
							setModelDialogOpen(true);
						}}
						onManageAccess={setAccessModel}
						onDelete={(model) => {
							deleteModel.mutate({ path: { id: model.id } });
						}}
					/>
				))}

			{settingsQuery.isError ? (
				<QueryErrorAlert
					error={settingsQuery.error}
					title="Could not load AI policy"
					onRetry={() => void settingsQuery.refetch()}
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
				open={connectionDialogOpen}
				onOpenChange={(open) => {
					setConnectionDialogOpen(open);
					if (!open) setProbedModels(null);
				}}
				editing={editingConnection}
				isSubmitting={createConnection.isPending || updateConnection.isPending}
				onCreate={(body: CreateLlmConnectionRequest) => createConnection.mutate({ body })}
				onUpdate={(id, body: UpdateLlmConnectionRequest) => {
					updateConnection.mutate({ path: { id }, body });
				}}
				isProbing={probeDraft.isPending || probeSaved.isPending}
				onProbe={(request, callbacks) => {
					probeDraft.mutate(
						{ body: request },
						{
							onSuccess: callbacks.onSuccess,
							onError: (error) =>
								callbacks.onError(problemDetailOf(error, "The provider didn't answer.")),
						},
					);
				}}
				onProbeSaved={(id, callbacks) => {
					probeSaved.mutate(
						{ path: { id } },
						{
							onSuccess: callbacks.onSuccess,
							onError: (error) =>
								callbacks.onError(problemDetailOf(error, "The provider didn't answer.")),
						},
					);
				}}
				onProbed={(models) =>
					setProbedModels({ connectionId: editingConnection?.id ?? null, models })
				}
			/>

			<AdminLlmModelFormDialog
				open={modelDialogOpen}
				onOpenChange={setModelDialogOpen}
				editing={editingModel}
				workspaceOptions={workspaceOptions}
				probedModelIds={
					probedModels && selectedConnection && probedModels.connectionId === selectedConnection.id
						? probedModels.models
						: []
				}
				isSubmitting={isModelSaving}
				onSave={(body) => void handleSaveModel(body)}
			/>

			<AdminLlmModelAccessDialog
				open={accessModel != null}
				onOpenChange={(open) => {
					if (!open) setAccessModel(null);
				}}
				model={accessModel}
				workspaceOptions={workspaceOptions}
				isLoadingWorkspaces={workspacesQuery.isLoading}
				workspacesError={workspacesQuery.error}
				onRetryWorkspaces={() => void workspacesQuery.refetch()}
				isSubmitting={updateSharing.isPending}
				onSave={(body) => {
					if (!accessModel) return;
					updateSharing.mutate(
						{ path: { id: accessModel.id }, body },
						{
							onSuccess: () => {
								void invalidateModels();
								setAccessModel(null);
								toast.success("Workspace access updated");
							},
							onError: (error) =>
								toast.error("Couldn't update workspace access", {
									description: problemDetailOf(error),
								}),
						},
					);
				}}
			/>

			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) setChallenge(undefined);
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
