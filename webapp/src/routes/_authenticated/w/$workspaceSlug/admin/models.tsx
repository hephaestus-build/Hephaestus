import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import {
	configureAgentMutation,
	deleteAgentMutation,
	getLlmUsageReportOptions,
	getWorkspaceOptions,
	listAgentsOptions,
	listAgentsQueryKey,
	workspaceGetLlmSettingsOptions,
	workspaceListAvailableLlmModelsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { AgentBinding } from "@/api/types.gen";
import { currentMonthUtc } from "@/components/admin/usage/usage-utils";
import {
	AgentBindingsPage,
	isPurpose,
	PURPOSE_TITLES,
} from "@/components/admin/workspace-llm/AgentBindingsPage";
import { WorkspaceLlmProviderPanel } from "@/components/admin/workspace-llm/WorkspaceLlmProviderPanel";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/models")({
	head: workspaceAdminHead("AI models"),
	component: ModelsContainer,
});

type Purpose = AgentBinding["purpose"];

const AGENT_WRITE_MUTATION_KEY = ["workspaceWriteAgent"];

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
	const usageQuery = useQuery({
		...getLlmUsageReportOptions({ path: { workspaceSlug }, query: { month: currentMonthUtc() } }),
		staleTime: 60_000,
	});

	const pageQueries = [bindingsQuery, workspaceQuery, llmSettingsQuery, availableModelsQuery];

	const bindingsKey = listAgentsQueryKey({ path: { workspaceSlug } });
	const invalidateBindings = async () => queryClient.invalidateQueries({ queryKey: bindingsKey });

	const cacheSavedBinding = (saved: AgentBinding) =>
		queryClient.setQueryData<AgentBinding[]>(bindingsKey, (current) => {
			const bindings = current ?? [];
			return bindings.some((b) => b.purpose === saved.purpose)
				? bindings.map((b) => (b.purpose === saved.purpose ? saved : b))
				: [...bindings, saved];
		});

	const dropCachedBinding = (purpose: Purpose) =>
		queryClient.setQueryData<AgentBinding[]>(bindingsKey, (current) =>
			(current ?? []).filter((b) => b.purpose !== purpose),
		);

	const [saveRevisions, setSaveRevisions] = useState<Partial<Record<Purpose, number>>>({});
	const bumpSaveRevision = (purpose: Purpose) =>
		setSaveRevisions((current) => ({ ...current, [purpose]: (current[purpose] ?? 0) + 1 }));

	const configureAgent = useMutation({
		...filedUnder(agentWriteKey, configureAgentMutation()),
		onSuccess: (saved, variables) => {
			cacheSavedBinding(saved);
			bumpSaveRevision(variables.path.purpose);
			void invalidateBindings();
			toast.success(`${PURPOSE_TITLES[variables.path.purpose]} saved`);
		},
		onError: (error, variables) => {
			toast.error(`Couldn't save ${PURPOSE_TITLES[variables.path.purpose].toLowerCase()}`, {
				description: problemDetailOf(error),
			});
		},
	});

	const deleteAgent = useMutation({
		...filedUnder(agentWriteKey, deleteAgentMutation()),
		onSuccess: (_data, variables) => {
			dropCachedBinding(variables.path.purpose);
			bumpSaveRevision(variables.path.purpose);
			void invalidateBindings();
			toast.success(`${PURPOSE_TITLES[variables.path.purpose]} turned off`);
		},
		onError: (error, variables) => {
			toast.error(`Couldn't turn off ${PURPOSE_TITLES[variables.path.purpose].toLowerCase()}`, {
				description: problemDetailOf(error),
			});
		},
	});

	const pendingPurposes = usePendingMutationIds(agentWriteKey, (variables) => {
		const purpose = pathString(variables, "purpose");
		return purpose !== undefined && isPurpose(purpose) ? purpose : undefined;
	});

	return (
		<AgentBindingsPage
			workspaceSlug={workspaceSlug}
			bindings={bindingsQuery.data ?? []}
			availableModels={availableModelsQuery.data ?? []}
			practicesEnabled={workspaceQuery.data?.practicesEnabled ?? false}
			mentorEnabled={workspaceQuery.data?.mentorEnabled ?? false}
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
			pendingPurposes={pendingPurposes}
			saveRevisions={saveRevisions}
			onRetry={() => {
				for (const query of pageQueries) {
					void query.refetch();
				}
			}}
			onSave={(purpose, body) => configureAgent.mutate({ path: { workspaceSlug, purpose }, body })}
			onTurnOff={(purpose) => deleteAgent.mutate({ path: { workspaceSlug, purpose } })}
		/>
	);
}
