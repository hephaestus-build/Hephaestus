import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	getLlmUsageReportOptions,
	getMemberOnboardingSettingsOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listAgentsQueryKey,
	workspaceGetLlmSettingsOptions,
	workspaceListAvailableLlmModelsOptions,
} from "@/api/@tanstack/react-query.gen";
import { configureAgent, deleteAgent } from "@/api/sdk.gen";
import type { AgentBinding, AgentBindingRequest } from "@/api/types.gen";
import {
	AgentBindingsPage,
	type BindingTarget,
	bindingTargetKey,
	isPurpose,
	PURPOSE_TITLES,
} from "@/components/admin/ai/AgentBindingsPage";
import { WorkspaceLlmProviderPanel } from "@/components/admin/ai/WorkspaceLlmProviderPanel";
import { currentMonthUtc } from "@/components/admin/usage/usage-utils";
import {
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";
import { usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/models")({
	head: workspaceAdminHead("AI models"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ModelsContainer,
});

const AGENT_WRITE_MUTATION_KEY = ["workspaceWriteAgent"];

const isDataHandlingTier = (value: unknown): value is DataHandlingTier =>
	typeof value === "string" && DATA_HANDLING_TIERS.some((tier) => tier === value);

/** Both writes name the row they are about, so the pending set and the caches key on it. */
interface BindingWrite {
	target: BindingTarget;
}

interface BindingSave extends BindingWrite {
	body: AgentBindingRequest;
}

/** The mutation cache types `variables` as `unknown`; only a write that names a real row counts. */
function targetKeyOf(variables: unknown): string | undefined {
	if (!isRecord(variables) || !isRecord(variables.target)) return undefined;
	const { purpose, tier } = variables.target;
	return typeof purpose === "string" && isPurpose(purpose) && isDataHandlingTier(tier)
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
		? `This model is declared as ${DATA_HANDLING_DEFS[declaredTier].label}; assign it to that row.`
		: problemDetailOf(error);
}

const sameSlot = (a: AgentBinding, b: BindingTarget) =>
	a.purpose === b.purpose && a.dataHandlingTier === b.tier;

function ModelsContainer() {
	const { workspaceSlug } = Route.useParams();
	const queryClient = useQueryClient();
	const agentWriteKey = [...AGENT_WRITE_MUTATION_KEY, workspaceSlug];

	const bindingsQuery = useQuery(listAgentsOptions({ path: { workspaceSlug } }));
	const workspaceQuery = useQuery(getWorkspaceOptions({ path: { workspaceSlug } }));
	const llmSettingsQuery = useQuery(workspaceGetLlmSettingsOptions({ path: { workspaceSlug } }));
	const availableModelsQuery = useQuery(
		workspaceListAvailableLlmModelsOptions({ path: { workspaceSlug } }),
	);
	const onboardingSettingsQuery = useQuery(
		getMemberOnboardingSettingsOptions({ path: { workspaceSlug } }),
	);
	const usageQuery = useQuery({
		...getLlmUsageReportOptions({ path: { workspaceSlug }, query: { month: currentMonthUtc() } }),
		staleTime: 60_000,
	});

	const pageQueries = [
		bindingsQuery,
		workspaceQuery,
		llmSettingsQuery,
		availableModelsQuery,
		onboardingSettingsQuery,
	];

	const agentsKey = listAgentsQueryKey({ path: { workspaceSlug } });
	const invalidateBindings = () => queryClient.invalidateQueries({ queryKey: agentsKey });

	const cacheSavedBinding = (saved: AgentBinding) =>
		queryClient.setQueryData<AgentBinding[]>(agentsKey, (current) => {
			const bindings = current ?? [];
			const slot = { purpose: saved.purpose, tier: saved.dataHandlingTier };
			return bindings.some((b) => sameSlot(b, slot))
				? bindings.map((b) => (sameSlot(b, slot) ? saved : b))
				: [...bindings, saved];
		});

	const dropCachedBinding = (target: BindingTarget) =>
		queryClient.setQueryData<AgentBinding[]>(agentsKey, (current) =>
			(current ?? []).filter((b) => !sameSlot(b, target)),
		);

	const [saveRevisions, setSaveRevisions] = useState<Partial<Record<string, number>>>({});
	const bumpSaveRevision = (target: BindingTarget) => {
		const key = bindingTargetKey(target);
		setSaveRevisions((current) => ({ ...current, [key]: (current[key] ?? 0) + 1 }));
	};

	const [saveErrors, setSaveErrors] = useState<Partial<Record<string, string>>>({});
	const setSaveError = (target: BindingTarget, error: string | undefined) =>
		setSaveErrors((current) => ({ ...current, [bindingTargetKey(target)]: error }));

	const saveBinding = useMutation({
		mutationKey: agentWriteKey,
		mutationFn: async ({ target, body }: BindingSave) => {
			const { data } = await configureAgent({
				path: { workspaceSlug, purpose: target.purpose },
				query: { dataHandlingTier: target.tier },
				body,
				throwOnError: true,
			});
			return data;
		},
		onSuccess: (saved, { target }) => {
			cacheSavedBinding(saved);
			bumpSaveRevision(target);
			void invalidateBindings();
			toast.success(`${PURPOSE_TITLES[target.purpose]} saved`);
		},
		onError: (error, { target }) => {
			// The slot refusal is about the row's own picker, so it stays on the row.
			if (problemStatusOf(error) === 409) {
				setSaveError(target, slotRefusalOf(error));
				return;
			}
			toast.error(`Couldn't save ${PURPOSE_TITLES[target.purpose].toLowerCase()}`, {
				description: problemDetailOf(error),
			});
		},
	});

	const clearBinding = useMutation({
		mutationKey: agentWriteKey,
		mutationFn: async ({ target }: BindingWrite) => {
			await deleteAgent({
				path: { workspaceSlug, purpose: target.purpose },
				query: { dataHandlingTier: target.tier },
				throwOnError: true,
			});
		},
		onSuccess: (_data, { target }) => {
			dropCachedBinding(target);
			bumpSaveRevision(target);
			void invalidateBindings();
			toast.success(`${PURPOSE_TITLES[target.purpose]} turned off`);
		},
		onError: (error, { target }) => {
			toast.error(`Couldn't turn off ${PURPOSE_TITLES[target.purpose].toLowerCase()}`, {
				description: problemDetailOf(error),
			});
		},
	});

	const pendingTargets = usePendingMutationIds(agentWriteKey, targetKeyOf);

	return (
		<AgentBindingsPage
			workspaceSlug={workspaceSlug}
			bindings={bindingsQuery.data ?? []}
			availableModels={availableModelsQuery.data ?? []}
			practicesEnabled={workspaceQuery.data?.practicesEnabled ?? false}
			mentorEnabled={workspaceQuery.data?.mentorEnabled ?? false}
			aiChoiceRequired={onboardingSettingsQuery.data?.aiChoiceRequired ?? false}
			providerPanel={
				<WorkspaceLlmProviderPanel
					workspaceSlug={workspaceSlug}
					ownProviderAllowed={llmSettingsQuery.data?.ownProviderAllowed ?? false}
				/>
			}
			usage={usageQuery.data}
			isLoading={pageQueries.some((query) => query.isLoading)}
			isError={pageQueries.some((query) => query.isError)}
			loadError={pageQueries.find((query) => query.error != null)?.error}
			pendingTargets={pendingTargets}
			saveRevisions={saveRevisions}
			saveErrors={saveErrors}
			onRetry={() => {
				for (const query of pageQueries) {
					void query.refetch();
				}
			}}
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
