import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { z } from "zod";
import { QueryErrorAlert, queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { isPracticeScriptApi } from "@/lib/llm-api-protocol-labels";
import { toolChoiceTestState } from "@/lib/llm-tool-choice-test";
import { toast } from "@/lib/toast";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import {
	getLlmUsageReportOptions,
	getLlmUsageReportQueryKey,
	getMemberOnboardingOptions,
	getMemberOnboardingSettingsOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listAgentsQueryKey,
	listPracticeScriptNeedsOptions,
	listPracticeScriptNeedsQueryKey,
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
	workspaceProbeLlmModelCapabilitiesMutation,
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
import { type LoadState, panelState } from "@/components/common/panel-state";
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

	const saveKey = [...AGENT_WRITE_MUTATION_KEY, workspaceSlug, "save"];
	const clearKey = [...AGENT_WRITE_MUTATION_KEY, workspaceSlug, "clear"];

	const bindingsOptions = listAgentsOptions({ path: { workspaceSlug } });
	const workspaceOptions = getWorkspaceOptions({ path: { workspaceSlug } });
	const llmSettingsOptions = workspaceGetLlmSettingsOptions({ path: { workspaceSlug } });
	const availableModelsOptions = workspaceListAvailableLlmModelsOptions({
		path: { workspaceSlug },
	});
	const onboardingSettingsOptions = getMemberOnboardingSettingsOptions({
		path: { workspaceSlug },
	});
	const bindingsQuery = useQuery(bindingsOptions);
	const workspaceQuery = useQuery(workspaceOptions);
	const llmSettingsQuery = useQuery(llmSettingsOptions);
	const availableModelsQuery = useQuery(availableModelsOptions);
	const onboardingSettingsQuery = useQuery(onboardingSettingsOptions);
	useQueryReadRefusal(bindingsOptions.queryKey, bindingsQuery.isError, bindingsQuery.error);
	useQueryReadRefusal(workspaceOptions.queryKey, workspaceQuery.isError, workspaceQuery.error);
	useQueryReadRefusal(
		llmSettingsOptions.queryKey,
		llmSettingsQuery.isError,
		llmSettingsQuery.error,
	);
	useQueryReadRefusal(
		availableModelsOptions.queryKey,
		availableModelsQuery.isError,
		availableModelsQuery.error,
	);
	useQueryReadRefusal(
		onboardingSettingsOptions.queryKey,
		onboardingSettingsQuery.isError,
		onboardingSettingsQuery.error,
	);
	const practiceScriptNeedsQuery = useQuery(
		listPracticeScriptNeedsOptions({ path: { workspaceSlug } }),
	);
	const usageQuery = useQuery({
		...getLlmUsageReportOptions({ path: { workspaceSlug }, query: { month: currentMonthUtc() } }),
		staleTime: 60_000,
	});

	// The practice script needs are not among them: the page binds models without them, so their failure
	// shows only in the section *Models for practice scripts*.
	const pageQueries = [
		bindingsQuery,
		workspaceQuery,
		llmSettingsQuery,
		availableModelsQuery,
		onboardingSettingsQuery,
	];
	const failedQueries = pageQueries.filter((query) => query.isError);
	const loaded = pageQueries.every((query) => query.data !== undefined);
	// A refusal hides the page even over cached data. Any other failure leaves loaded rows, and the
	// drafts in them, on screen beside the retry; a failure before anything loaded still blocks.
	const blockingFailure =
		failedQueries.find((query) => !queryErrorIsRetryable(query.error)) ??
		(loaded ? undefined : failedQueries[0]);
	const retryFailed = () => {
		for (const query of failedQueries) {
			void query.refetch();
		}
	};

	const invalidateBindings = async () =>
		Promise.all([
			queryClient.invalidateQueries({ queryKey: listAgentsQueryKey({ path: { workspaceSlug } }) }),
			queryClient.invalidateQueries(getMemberOnboardingOptions({ path: { workspaceSlug } })),
			queryClient.invalidateQueries({
				queryKey: listPracticeScriptNeedsQueryKey({ path: { workspaceSlug } }),
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

	// A binding write changes sibling routes; wait for the whole set before reseeding forms.
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

	let pageState: LoadState = { status: "loading" };
	if (blockingFailure) {
		pageState = { status: "error", error: blockingFailure.error, onRetry: retryFailed };
	} else if (loaded) {
		pageState = { status: "ready" };
	}
	const refreshFailure: RefreshFailure | undefined =
		!blockingFailure && failedQueries.length > 0
			? {
					error: failedQueries[0]?.error,
					pending: failedQueries.some((q) => q.isFetching),
					onRetry: retryFailed,
				}
			: undefined;
	return (
		<AgentBindingsPage
			workspaceSlug={workspaceSlug}
			state={pageState}
			refreshFailure={refreshFailure}
			bindings={bindingsQuery.data ?? []}
			availableModels={availableModelsQuery.data ?? []}
			practicesEnabled={workspaceQuery.data?.practicesEnabled ?? false}
			aiChoiceRequired={onboardingSettingsQuery.data?.aiChoiceRequired ?? false}
			practiceScriptNeeds={panelState(practiceScriptNeedsQuery, (summaries) => ({
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

// Key pending writes by row so concurrent requests cannot clear another row's busy state.
const PROBE_MUTATION_KEY = ["workspaceProbeLlmConnection"];
const MODEL_WRITE_MUTATION_KEY = ["workspaceWriteLlmModel"];
const MODEL_CREATE_MUTATION_KEY = ["workspaceCreateLlmModel"];
const CONNECTION_WRITE_MUTATION_KEY = ["workspaceWriteLlmConnection"];

const idOf = (variables: unknown) => pathNumber(variables, "id");
const connectionIdOf = (variables: unknown) => pathNumber(variables, "connectionId");

/**
 * What an open form edits. Each opening is a new object, so a save that finishes after its form was
 * dismissed finds a different subject and leaves the form now open alone.
 */
type ConnectionForm = { kind: "create" } | { kind: "edit"; connection: WorkspaceLlmConnection };
type ModelForm =
	| { kind: "create"; connectionId: number }
	| { kind: "edit"; model: WorkspaceLlmModel };

/** Closes the form only if it still shows the subject that was saved. */
function closedIf<T>(saved: T | null) {
	return function closeSaved(current: T | null) {
		return current === saved ? null : current;
	};
}

interface WorkspaceProvidersProps {
	workspaceSlug: string;
	ownProviderAllowed: boolean;
	onWrite: () => Promise<unknown>;
}

function WorkspaceProviders({
	workspaceSlug,
	ownProviderAllowed,
	onWrite,
}: WorkspaceProvidersProps) {
	const queryClient = useQueryClient();
	const capabilityProbe = useMutation({
		...workspaceProbeLlmModelCapabilitiesMutation(),
		onSuccess: async () =>
			queryClient.invalidateQueries({
				queryKey: workspaceListLlmModelsQueryKey({ path: { workspaceSlug } }),
			}),
	});

	const path = { workspaceSlug };
	const modelWriteKey = [...MODEL_WRITE_MUTATION_KEY, workspaceSlug];
	const connectionWriteKey = [...CONNECTION_WRITE_MUTATION_KEY, workspaceSlug];
	const probeKey = [...PROBE_MUTATION_KEY, workspaceSlug];
	const modelCreateKey = [...MODEL_CREATE_MUTATION_KEY, workspaceSlug];
	const [connectionForm, setConnectionForm] = useState<ConnectionForm | null>(null);
	const [modelForm, setModelForm] = useState<ModelForm | null>(null);
	// The instance can withdraw registration while the page is open; a refused create says so.
	const [registrationRefused, setRegistrationRefused] = useState(false);
	const [testResults, setTestResults] = useState(() => new Map<number, ProviderTestResult>());

	const connectionsOptions = workspaceListLlmConnectionsOptions({ path });
	const connectionsQuery = useQuery(connectionsOptions);
	useQueryReadRefusal(
		connectionsOptions.queryKey,
		connectionsQuery.isError,
		connectionsQuery.error,
	);
	const connections = connectionsQuery.data ?? [];
	const modelsOptions = workspaceListLlmModelsOptions({ path });
	const modelsQuery = useQuery({ ...modelsOptions, enabled: connections.length > 0 });
	useQueryReadRefusal(modelsOptions.queryKey, modelsQuery.isError, modelsQuery.error);
	const requiredReads =
		connections.length > 0 ? [connectionsQuery, modelsQuery] : [connectionsQuery];
	const failedReads = requiredReads.filter((read) => read.isError);
	const loaded =
		connectionsQuery.data !== undefined &&
		(connections.length === 0 || modelsQuery.data !== undefined);
	const blockingFailure =
		failedReads.find((read) => !queryErrorIsRetryable(read.error)) ??
		(loaded ? undefined : failedReads[0]);
	const retryPending = failedReads.some((read) => read.isFetching);
	const retryFailedReads = () => {
		for (const read of failedReads) {
			void read.refetch();
		}
	};
	if (
		blockingFailure !== undefined &&
		!queryErrorIsRetryable(blockingFailure.error) &&
		(connectionForm !== null || modelForm !== null)
	) {
		setConnectionForm(null);
		setModelForm(null);
	}

	const invalidateProviders = async () =>
		Promise.all([
			queryClient.invalidateQueries({ queryKey: workspaceListLlmConnectionsQueryKey({ path }) }),
			queryClient.invalidateQueries({ queryKey: workspaceListLlmModelsQueryKey({ path }) }),
			queryClient.invalidateQueries({
				queryKey: workspaceListAvailableLlmModelsQueryKey({ path }),
			}),
			queryClient.invalidateQueries({ queryKey: getLlmUsageReportQueryKey({ path }) }),
			onWrite(),
		]);

	const createConnection = useMutation({
		...workspaceCreateLlmConnectionMutation(),
		onSuccess: () => {
			void invalidateProviders();
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
		...filedUnder(modelCreateKey, workspaceCreateLlmModelMutation()),
		onSuccess: () => {
			void invalidateProviders();
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
	const addingModelConnectionIds = usePendingMutationIds(modelCreateKey, connectionIdOf);

	// A form is busy only while its own subject saves: another row's write is not its business.
	const connectionFormSubmitting =
		connectionForm?.kind === "edit"
			? writingConnectionIds.has(connectionForm.connection.id)
			: createConnection.isPending;
	const modelFormSubmitting =
		modelForm?.kind === "edit"
			? writingModelIds.has(modelForm.model.id)
			: modelForm !== null && addingModelConnectionIds.has(modelForm.connectionId);
	// The save outlives its form, which may be dismissed meanwhile; its result closes only that form.
	// Each mutation's own onError reports a failure, so the form stays open on it.
	const saveConnectionForm = (saved: ConnectionForm | null, request: Promise<unknown>) => {
		void request.then(
			() => setConnectionForm(closedIf(saved)),
			() => undefined,
		);
	};
	const saveModelForm = (saved: ModelForm | null, request: Promise<unknown>) => {
		void request.then(
			() => setModelForm(closedIf(saved)),
			() => undefined,
		);
	};

	const refreshFailure: RefreshFailure | undefined =
		blockingFailure === undefined && failedReads.length > 0
			? { error: failedReads[0]?.error, pending: retryPending, onRetry: retryFailedReads }
			: undefined;
	const formOpen = connectionForm !== null || modelForm !== null;
	const refreshNotice =
		refreshFailure === undefined ? undefined : (
			<QueryErrorAlert
				error={refreshFailure.error}
				title="We could not refresh your AI providers"
				pending={refreshFailure.pending}
				onRetry={refreshFailure.onRetry}
			/>
		);
	let providersState: ProviderPanelState = { status: "loading" };
	if (blockingFailure !== undefined) {
		providersState = { status: "error", error: blockingFailure.error, onRetry: retryFailedReads };
	} else if (loaded) {
		providersState = {
			status: "ready",
			connections,
			models: modelsQuery.data ?? [],
			refreshFailure: formOpen ? undefined : refreshFailure,
		};
	}

	return (
		<>
			<WorkspaceLlmProviderPanel
				state={providersState}
				registrationAllowed={ownProviderAllowed && !registrationRefused}
				testResults={testResults}
				testingConnectionIds={testingConnectionIds}
				writingConnectionIds={writingConnectionIds}
				writingModelIds={writingModelIds}
				addingConnection={createConnection.isPending}
				addingModelConnectionIds={addingModelConnectionIds}
				onAddConnection={() => setConnectionForm({ kind: "create" })}
				onEditConnection={(connection) => setConnectionForm({ kind: "edit", connection })}
				onTestConnection={(connection) => {
					setTestResult(connection.id, undefined);
					probeConnection.mutate({ path: { workspaceSlug, id: connection.id } });
				}}
				onDisconnect={(connection) =>
					deleteConnection.mutate({ path: { workspaceSlug, id: connection.id } })
				}
				onAddModel={(connection) => setModelForm({ kind: "create", connectionId: connection.id })}
				onEditModel={(model) => setModelForm({ kind: "edit", model })}
				onDeleteModel={(model) => deleteModel.mutate({ path: { workspaceSlug, id: model.id } })}
			/>
			<WorkspaceLlmConnectionFormDialog
				open={connectionForm !== null}
				notice={refreshNotice}
				onOpenChange={(open) => {
					if (!open) {
						setConnectionForm(null);
					}
				}}
				editing={connectionForm?.kind === "edit" ? connectionForm.connection : null}
				isSubmitting={connectionFormSubmitting}
				onCreate={(body) =>
					saveConnectionForm(connectionForm, createConnection.mutateAsync({ path, body }))
				}
				onUpdate={(id, body) =>
					saveConnectionForm(
						connectionForm,
						updateConnection.mutateAsync({ path: { workspaceSlug, id }, body }),
					)
				}
			/>
			<WorkspaceLlmModelFormDialog
				open={modelForm !== null}
				notice={refreshNotice}
				onOpenChange={(open) => {
					if (!open) {
						capabilityProbe.reset();
						setModelForm(null);
					}
				}}
				editing={modelForm?.kind === "edit" ? modelForm.model : null}
				capabilities={
					modelForm?.kind === "edit" &&
					connections.some(
						(connection) =>
							connection.id === modelForm.model.connectionId &&
							!isPracticeScriptApi(connection.apiProtocol) &&
							connection.apiProtocol !== "anthropic-messages",
					)
						? {
								supported: modelsQuery.data?.find((model) => model.id === modelForm.model.id)
									?.forcedToolChoice,
								test: toolChoiceTestState(modelForm.model.id, capabilityProbe),
								onProbe: () =>
									capabilityProbe.mutate({ path: { workspaceSlug, id: modelForm.model.id } }),
							}
						: undefined
				}
				isSubmitting={modelFormSubmitting}
				onCreate={(body) => {
					if (modelForm?.kind !== "create") {
						return;
					}
					saveModelForm(
						modelForm,
						createModel.mutateAsync({
							path: { workspaceSlug, connectionId: modelForm.connectionId },
							body,
						}),
					);
				}}
				onUpdate={(id, body) =>
					saveModelForm(modelForm, updateModel.mutateAsync({ path: { workspaceSlug, id }, body }))
				}
			/>
		</>
	);
}
