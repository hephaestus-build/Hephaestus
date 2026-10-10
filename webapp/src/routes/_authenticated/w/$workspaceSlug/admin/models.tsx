import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import {
	getLlmUsageReportOptions,
	getLlmUsageReportQueryKey,
	getMemberOnboardingOptions,
	getMemberOnboardingSettingsOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listAgentsQueryKey,
	listPrecomputeNeedsOptions,
	listPrecomputeNeedsQueryKey,
	workspaceCreateLlmConnectionMutation,
	workspaceCreateLlmModelMutation,
	workspaceDeleteLlmConnectionMutation,
	workspaceDeleteLlmModelMutation,
	workspaceGetLlmSettingsOptions,
	workspaceListAvailableLlmModelsOptions,
	workspaceListAvailableLlmModelsQueryKey,
	workspaceListLlmConnectionsOptions,
	workspaceListLlmConnectionsQueryKey,
	workspaceListLlmModelsOptions,
	workspaceListLlmModelsQueryKey,
	workspaceProbeLlmConnectionMutation,
	workspaceUpdateLlmConnectionMutation,
	workspaceUpdateLlmModelMutation,
} from "@/api/@tanstack/react-query.gen";
import { configureAgent, deleteAgent } from "@/api/sdk.gen";
import type {
	AgentBindingRequest,
	WorkspaceLlmConnection,
	WorkspaceLlmModel,
} from "@/api/types.gen";
import { currentMonthUtc } from "@/components/admin/usage/usage-utils";
import {
	AgentBindingsPage,
	type BindingTarget,
	type BindingWrite,
	bindingTargetKey,
} from "@/components/admin/workspace-llm/AgentBindingsPage";
import { WorkspaceLlmConnectionFormDialog } from "@/components/admin/workspace-llm/WorkspaceLlmConnectionFormDialog";
import { WorkspaceLlmModelFormDialog } from "@/components/admin/workspace-llm/WorkspaceLlmModelFormDialog";
import {
	type ProviderPanelState,
	type ProviderTestResult,
	WorkspaceLlmProviderPanel,
} from "@/components/admin/workspace-llm/WorkspaceLlmProviderPanel";
import { combinePanelStates, panelState, queryLoadState } from "@/components/common/panel-state";
import {
	AGENT_PURPOSE_DEFS,
	AGENT_PURPOSES,
	isAgentPurpose,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";
import { filedUnder, pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";

/** `?purpose=` opens that purpose's row and scrolls to it; a fix link elsewhere points here. */
const modelsSearchSchema = z.object({
	purpose: z.enum(AGENT_PURPOSES).optional().catch(undefined),
});

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/models")({
	head: workspaceAdminHead("AI models"),
	validateSearch: modelsSearchSchema,
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ModelsContainer,
});

const AGENT_WRITE_MUTATION_KEY = ["workspaceWriteAgent"];

const isDataHandlingTier = (value: unknown): value is DataHandlingTier =>
	typeof value === "string" && DATA_HANDLING_TIERS.some((tier) => tier === value);

/** Both writes name the row they are about, so the pending set keys on it. */
interface BindingClear {
	target: BindingTarget;
}

interface BindingSave extends BindingClear {
	body: AgentBindingRequest;
}

/** The mutation cache types `variables` as `unknown`; only a write that names a real row counts. */
function targetKeyOf(variables: unknown): string | undefined {
	if (!isRecord(variables) || !isRecord(variables.target)) {
		return undefined;
	}
	const { purpose, tier } = variables.target;
	return typeof purpose === "string" && isAgentPurpose(purpose) && isDataHandlingTier(tier)
		? bindingTargetKey({ purpose, tier })
		: undefined;
}

/**
 * The slot refusal names the model's declared tier as a property of the problem body, so the row
 * can say it in the registry's words. A refusal without one is shown as the server phrased it.
 */
function slotRefusalOf(error: unknown): string {
	const declaredTier = isRecord(error) ? error.declaredTier : undefined;
	return isDataHandlingTier(declaredTier)
		? `This model is declared as ${DATA_HANDLING_DEFS[declaredTier].label}. Assign it under ${DATA_HANDLING_DEFS[declaredTier].label}.`
		: problemDetailOf(error);
}

function ModelsContainer() {
	const { workspaceSlug } = Route.useParams();
	const { purpose: focusPurpose } = Route.useSearch();
	const setSearch = useSearchState();
	const queryClient = useQueryClient();
	// One key for each write, so the row can say which one it waits on.
	const saveKey = [...AGENT_WRITE_MUTATION_KEY, workspaceSlug, "save"];
	const clearKey = [...AGENT_WRITE_MUTATION_KEY, workspaceSlug, "clear"];

	const bindingsQuery = useQuery(listAgentsOptions({ path: { workspaceSlug } }));
	const workspaceQuery = useQuery(getWorkspaceOptions({ path: { workspaceSlug } }));
	const llmSettingsQuery = useQuery(workspaceGetLlmSettingsOptions({ path: { workspaceSlug } }));
	const availableModelsQuery = useQuery(
		workspaceListAvailableLlmModelsOptions({ path: { workspaceSlug } }),
	);
	const onboardingSettingsQuery = useQuery(
		getMemberOnboardingSettingsOptions({ path: { workspaceSlug } }),
	);
	const precomputeNeedsQuery = useQuery(listPrecomputeNeedsOptions({ path: { workspaceSlug } }));
	const usageQuery = useQuery({
		...getLlmUsageReportOptions({ path: { workspaceSlug }, query: { month: currentMonthUtc() } }),
		staleTime: 60_000,
	});

	// The precompute needs are not among them: the page binds models without them, so their failure
	// shows only in the precompute section.
	const pageQueries = [
		bindingsQuery,
		workspaceQuery,
		llmSettingsQuery,
		availableModelsQuery,
		onboardingSettingsQuery,
	];

	// Readiness per AI choice lives on the member setup, which decides whether Heph is in the
	// navigation. Which members a practice's required model misses follows the bindings too.
	const invalidateBindings = async () =>
		Promise.all([
			queryClient.invalidateQueries({ queryKey: listAgentsQueryKey({ path: { workspaceSlug } }) }),
			queryClient.invalidateQueries(getMemberOnboardingOptions({ path: { workspaceSlug } })),
			queryClient.invalidateQueries({
				queryKey: listPrecomputeNeedsQueryKey({ path: { workspaceSlug } }),
			}),
		]);

	const [saveRevisions, setSaveRevisions] = useState<Partial<Record<string, number>>>({});
	const bumpSaveRevision = (target: BindingTarget) => {
		const key = bindingTargetKey(target);
		setSaveRevisions((current) => ({ ...current, [key]: (current[key] ?? 0) + 1 }));
	};

	const [saveErrors, setSaveErrors] = useState<Partial<Record<string, string>>>({});
	const setSaveError = (target: BindingTarget, error: string | undefined) =>
		setSaveErrors((current) => ({ ...current, [bindingTargetKey(target)]: error }));

	// A write changes whom its siblings serve too, so the row waits for the whole set to come back
	// before it reseeds; the PUT's answer alone would mix new and old `servedTiers`.
	const saveBinding = useMutation({
		mutationKey: saveKey,
		mutationFn: async ({ target, body }: BindingSave) => {
			await configureAgent({
				path: { workspaceSlug, purpose: target.purpose },
				query: { dataHandlingTier: target.tier },
				body,
				throwOnError: true,
			});
		},
		onSuccess: async (_data, { target }) => {
			await invalidateBindings();
			bumpSaveRevision(target);
			toast.success(`Assignment saved for ${AGENT_PURPOSE_DEFS[target.purpose].title}`);
		},
		onError: (error, { target }) => {
			// A refusal of the row's own choice stays on the row: the slot's tier (409), or a model
			// that cannot serve the purpose (400). Anything else is not about the choice.
			const status = problemStatusOf(error);
			if (status === 409) {
				setSaveError(target, slotRefusalOf(error));
				return;
			}
			if (status === 400) {
				setSaveError(target, problemDetailOf(error));
				return;
			}
			toast.error(
				`We could not save the assignment for ${AGENT_PURPOSE_DEFS[target.purpose].title}`,
				{
					description: problemDetailOf(error),
				},
			);
		},
	});

	const clearBinding = useMutation({
		mutationKey: clearKey,
		mutationFn: async ({ target }: BindingClear) => {
			await deleteAgent({
				path: { workspaceSlug, purpose: target.purpose },
				query: { dataHandlingTier: target.tier },
				throwOnError: true,
			});
		},
		onSuccess: async (_data, { target }) => {
			await invalidateBindings();
			bumpSaveRevision(target);
			toast.success(`Assignment cleared for ${AGENT_PURPOSE_DEFS[target.purpose].title}`);
		},
		onError: (error, { target }) => {
			toast.error(
				`We could not clear the assignment for ${AGENT_PURPOSE_DEFS[target.purpose].title}`,
				{
					description: problemDetailOf(error),
				},
			);
		},
	});

	const savingTargets = usePendingMutationIds(saveKey, targetKeyOf);
	const clearingTargets = usePendingMutationIds(clearKey, targetKeyOf);
	const pendingWrites = new Map<string, BindingWrite>([
		...[...clearingTargets].map((key) => [key, "CLEAR"] as const),
		...[...savingTargets].map((key) => [key, "SAVE"] as const),
	]);

	return (
		<AgentBindingsPage
			workspaceSlug={workspaceSlug}
			state={combinePanelStates(pageQueries.map(queryLoadState))}
			bindings={bindingsQuery.data ?? []}
			availableModels={availableModelsQuery.data ?? []}
			practicesEnabled={workspaceQuery.data?.practicesEnabled ?? false}
			aiChoiceRequired={onboardingSettingsQuery.data?.aiChoiceRequired ?? false}
			precomputeNeeds={panelState(precomputeNeedsQuery, (summaries) => ({
				status: "ready" as const,
				summaries,
			}))}
			ownProviderAllowed={llmSettingsQuery.data?.ownProviderAllowed ?? false}
			providerPanel={
				<WorkspaceProviders
					workspaceSlug={workspaceSlug}
					ownProviderAllowed={llmSettingsQuery.data?.ownProviderAllowed ?? false}
					onWrite={invalidateBindings}
				/>
			}
			usage={usageQuery.data}
			focusPurpose={focusPurpose}
			onFocusPurposeClosed={() => {
				void setSearch((previous) => ({ ...previous, purpose: undefined }), { replace: true });
			}}
			pendingWrites={pendingWrites}
			saveRevisions={saveRevisions}
			saveErrors={saveErrors}
			onSave={(target, body) => {
				setSaveError(target, undefined);
				saveBinding.mutate({ target, body });
			}}
			onTurnOff={(target) => {
				setSaveError(target, undefined);
				clearBinding.mutate({ target });
			}}
		/>
	);
}

// Each write is filed under a shared prefix so one cache lookup answers "is this row busy": a row
// stays disabled until *its own* write settles, not until whichever write settles first.
const PROBE_MUTATION_KEY = ["workspaceProbeLlmConnection"];
const MODEL_WRITE_MUTATION_KEY = ["workspaceWriteLlmModel"];
const CONNECTION_WRITE_MUTATION_KEY = ["workspaceWriteLlmConnection"];

const idOf = (variables: unknown) => pathNumber(variables, "id");

interface WorkspaceProvidersProps {
	workspaceSlug: string;
	ownProviderAllowed: boolean;
	/** A provider or model write can change which assignments are ready. */
	onWrite: () => Promise<unknown>;
}

/**
 * The workspace's own providers and models. Every write also refreshes the models the pickers
 * offer above, so a model added here can be assigned without a reload.
 */
function WorkspaceProviders({
	workspaceSlug,
	ownProviderAllowed,
	onWrite,
}: WorkspaceProvidersProps) {
	const queryClient = useQueryClient();
	const path = { workspaceSlug };
	const modelWriteKey = [...MODEL_WRITE_MUTATION_KEY, workspaceSlug];
	const connectionWriteKey = [...CONNECTION_WRITE_MUTATION_KEY, workspaceSlug];
	const probeKey = [...PROBE_MUTATION_KEY, workspaceSlug];
	const [connectionDialogOpen, setConnectionDialogOpen] = useState(false);
	const [editingConnection, setEditingConnection] = useState<WorkspaceLlmConnection | null>(null);
	const [modelDialogOpen, setModelDialogOpen] = useState(false);
	const [modelConnectionId, setModelConnectionId] = useState<number | null>(null);
	const [editingModel, setEditingModel] = useState<WorkspaceLlmModel | null>(null);
	// The instance can withdraw registration while the page is open; a refused create says so.
	const [registrationRefused, setRegistrationRefused] = useState(false);
	const [testResults, setTestResults] = useState(() => new Map<number, ProviderTestResult>());

	const connectionsQuery = useQuery(workspaceListLlmConnectionsOptions({ path }));
	const connections = connectionsQuery.data ?? [];
	const modelsQuery = useQuery({
		...workspaceListLlmModelsOptions({ path }),
		enabled: connections.length > 0,
	});

	const invalidateProviders = async () =>
		Promise.all([
			queryClient.invalidateQueries({ queryKey: workspaceListLlmConnectionsQueryKey({ path }) }),
			queryClient.invalidateQueries({ queryKey: workspaceListLlmModelsQueryKey({ path }) }),
			queryClient.invalidateQueries({
				queryKey: workspaceListAvailableLlmModelsQueryKey({ path }),
			}),
			// Whether the workspace's own provider is in use this month follows its connections and models.
			queryClient.invalidateQueries({ queryKey: getLlmUsageReportQueryKey({ path }) }),
			onWrite(),
		]);

	const createConnection = useMutation({
		...workspaceCreateLlmConnectionMutation(),
		onSuccess: () => {
			void invalidateProviders();
			setConnectionDialogOpen(false);
			toast.success("Provider connected");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 403) {
				setRegistrationRefused(true);
			}
			toast.error("We could not connect your provider", { description: problemDetailOf(error) });
		},
	});
	const updateConnection = useMutation({
		...filedUnder(connectionWriteKey, workspaceUpdateLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateProviders();
			setConnectionDialogOpen(false);
			toast.success("Provider updated");
		},
		onError: (error) =>
			toast.error("We could not update your provider", { description: problemDetailOf(error) }),
	});
	const deleteConnection = useMutation({
		...filedUnder(connectionWriteKey, workspaceDeleteLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateProviders();
			toast.success("Provider disconnected");
		},
		onError: (error) =>
			toast.error("We could not disconnect your provider", { description: problemDetailOf(error) }),
	});
	const setTestResult = (id: number, result: ProviderTestResult | undefined) =>
		setTestResults((current) => {
			const next = new Map(current);
			if (result === undefined) {
				next.delete(id);
			} else {
				next.set(id, result);
			}
			return next;
		});
	const probeConnection = useMutation({
		...filedUnder(probeKey, workspaceProbeLlmConnectionMutation()),
		onSuccess: (result, variables) =>
			setTestResult(variables.path.id, { status: "answered", result }),
		onError: (error, variables) =>
			setTestResult(variables.path.id, {
				status: "failed",
				message: problemDetailOf(error, "We could not reach the provider."),
			}),
	});

	const createModel = useMutation({
		...workspaceCreateLlmModelMutation(),
		onSuccess: () => {
			void invalidateProviders();
			setModelDialogOpen(false);
			toast.success("Model added");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 403) {
				setRegistrationRefused(true);
			}
			toast.error("We could not add the model", { description: problemDetailOf(error) });
		},
	});
	const updateModel = useMutation({
		...filedUnder(modelWriteKey, workspaceUpdateLlmModelMutation()),
		onSuccess: () => {
			void invalidateProviders();
			setModelDialogOpen(false);
			toast.success("Model updated");
		},
		onError: (error) =>
			toast.error("We could not update the model", { description: problemDetailOf(error) }),
	});
	const deleteModel = useMutation({
		...filedUnder(modelWriteKey, workspaceDeleteLlmModelMutation()),
		onSuccess: () => {
			void invalidateProviders();
			toast.success("Model deleted");
		},
		onError: (error) =>
			toast.error("We could not delete the model", { description: problemDetailOf(error) }),
	});

	const testingConnectionIds = usePendingMutationIds(probeKey, idOf);
	const writingConnectionIds = usePendingMutationIds(connectionWriteKey, idOf);
	const writingModelIds = usePendingMutationIds(modelWriteKey, idOf);

	// The models are asked for only once a connection exists, so with none there is nothing to wait for.
	const providersState: ProviderPanelState = panelState(connectionsQuery, (loaded) =>
		loaded.length === 0
			? { status: "ready" as const, connections: loaded, models: [] }
			: panelState(modelsQuery, (models) => ({
					status: "ready" as const,
					connections: loaded,
					models,
				})),
	);

	return (
		<>
			<WorkspaceLlmProviderPanel
				state={providersState}
				registrationAllowed={ownProviderAllowed && !registrationRefused}
				testResults={testResults}
				testingConnectionIds={testingConnectionIds}
				writingConnectionIds={writingConnectionIds}
				writingModelIds={writingModelIds}
				onAddConnection={() => {
					setEditingConnection(null);
					setConnectionDialogOpen(true);
				}}
				onEditConnection={(connection) => {
					setEditingConnection(connection);
					setConnectionDialogOpen(true);
				}}
				onTestConnection={(connection) => {
					setTestResult(connection.id, undefined);
					probeConnection.mutate({ path: { workspaceSlug, id: connection.id } });
				}}
				onDisconnect={(connection) =>
					deleteConnection.mutate({ path: { workspaceSlug, id: connection.id } })
				}
				onAddModel={(connection) => {
					setEditingModel(null);
					setModelConnectionId(connection.id);
					setModelDialogOpen(true);
				}}
				onEditModel={(model) => {
					setEditingModel(model);
					setModelConnectionId(model.connectionId);
					setModelDialogOpen(true);
				}}
				onDeleteModel={(model) => deleteModel.mutate({ path: { workspaceSlug, id: model.id } })}
			/>
			<WorkspaceLlmConnectionFormDialog
				open={connectionDialogOpen}
				onOpenChange={setConnectionDialogOpen}
				editing={editingConnection}
				isSubmitting={createConnection.isPending || updateConnection.isPending}
				onCreate={(body) => createConnection.mutate({ path, body })}
				onUpdate={(id, body) => updateConnection.mutate({ path: { workspaceSlug, id }, body })}
			/>
			<WorkspaceLlmModelFormDialog
				open={modelDialogOpen}
				onOpenChange={setModelDialogOpen}
				editing={editingModel}
				isSubmitting={createModel.isPending || updateModel.isPending}
				onCreate={(body) => {
					if (modelConnectionId == null) {
						return;
					}
					createModel.mutate({ path: { workspaceSlug, connectionId: modelConnectionId }, body });
				}}
				onUpdate={(id, body) => updateModel.mutate({ path: { workspaceSlug, id }, body })}
			/>
		</>
	);
}
