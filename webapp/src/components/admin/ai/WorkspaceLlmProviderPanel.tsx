import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Plug, Plus } from "lucide-react";
import { useId, useState } from "react";
import { toast } from "sonner";

import {
	workspaceCreateLlmConnectionMutation,
	workspaceCreateLlmModelMutation,
	workspaceDeleteLlmConnectionMutation,
	workspaceDeleteLlmModelMutation,
	workspaceListLlmConnectionsOptions,
	workspaceListLlmConnectionsQueryKey,
	workspaceListLlmModelsOptions,
	workspaceListLlmModelsQueryKey,
	workspaceProbeLlmConnectionMutation,
	workspaceUpdateLlmConnectionMutation,
	workspaceUpdateLlmModelMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	CreateWorkspaceLlmConnectionRequest,
	CreateWorkspaceLlmModelRequest,
	UpdateWorkspaceLlmConnectionRequest,
	UpdateWorkspaceLlmModelRequest,
	WorkspaceLlmConnection,
	WorkspaceLlmModel,
} from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { filedUnder, pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

import { WorkspaceLlmConnectionFormDialog } from "./WorkspaceLlmConnectionFormDialog";
import { WorkspaceLlmModelFormDialog } from "./WorkspaceLlmModelFormDialog";
import { WorkspaceLlmModelsTable } from "./WorkspaceLlmModelsTable";

export interface WorkspaceLlmProviderPanelProps {
	workspaceSlug: string;
	/**
	 * Whether the instance still lets this workspace register *new* providers and models. False does
	 * not hide the panel — providers already connected stay listed and editable.
	 */
	ownProviderAllowed: boolean;
}

interface TestResult {
	ok: boolean;
	message: string;
}

// Each write is filed under a shared prefix so one cache lookup answers "is this row busy" — a row
// stays disabled until *its own* write settles, not until whichever write settles first. The filing
// key and the key the lookup reads are both built from these: were they to drift, the lookup would
// return an empty set and re-enable every row mid-flight.
const PROBE_MUTATION_KEY = ["workspaceProbeLlmConnection"];
const MODEL_WRITE_MUTATION_KEY = ["workspaceWriteLlmModel"];
const CONNECTION_WRITE_MUTATION_KEY = ["workspaceWriteLlmConnection"];

export function WorkspaceLlmProviderPanel({
	workspaceSlug,
	ownProviderAllowed,
}: WorkspaceLlmProviderPanelProps) {
	const queryClient = useQueryClient();
	const cardLabelPrefix = useId();
	const modelWriteKey = [...MODEL_WRITE_MUTATION_KEY, workspaceSlug];
	const connectionWriteKey = [...CONNECTION_WRITE_MUTATION_KEY, workspaceSlug];
	const probeKey = [...PROBE_MUTATION_KEY, workspaceSlug];
	const [connectionDialogOpen, setConnectionDialogOpen] = useState(false);
	const [editingConnection, setEditingConnection] = useState<WorkspaceLlmConnection | null>(null);
	const [modelDialogOpen, setModelDialogOpen] = useState(false);
	const [modelConnectionId, setModelConnectionId] = useState<number | null>(null);
	const [editingModel, setEditingModel] = useState<WorkspaceLlmModel | null>(null);
	const [registrationDisabled, setRegistrationDisabled] = useState(false);
	const [testResults, setTestResults] = useState(() => new Map<number, TestResult>());
	const [deletingConnection, setDeletingConnection] = useState<WorkspaceLlmConnection | null>(null);
	const registrationBlocked = !ownProviderAllowed || registrationDisabled;

	const connectionsQuery = useQuery(
		workspaceListLlmConnectionsOptions({ path: { workspaceSlug } }),
	);
	const connections = connectionsQuery.data ?? [];
	const modelsQuery = useQuery({
		...workspaceListLlmModelsOptions({ path: { workspaceSlug } }),
		enabled: connections.length > 0,
	});
	const models = modelsQuery.data ?? [];

	const invalidateConnections = () =>
		queryClient.invalidateQueries({
			queryKey: workspaceListLlmConnectionsQueryKey({ path: { workspaceSlug } }),
		});
	const invalidateModels = () =>
		queryClient.invalidateQueries({
			queryKey: workspaceListLlmModelsQueryKey({ path: { workspaceSlug } }),
		});

	const createConnection = useMutation({
		...workspaceCreateLlmConnectionMutation(),
		onSuccess: () => {
			void invalidateConnections();
			setConnectionDialogOpen(false);
			toast.success("Provider connected");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 403) {
				setRegistrationDisabled(true);
			}
			toast.error("Couldn't connect your provider", { description: problemDetailOf(error) });
		},
	});
	const updateConnection = useMutation({
		...filedUnder(connectionWriteKey, workspaceUpdateLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateConnections();
			setConnectionDialogOpen(false);
			toast.success("Provider updated");
		},
		onError: (error) =>
			toast.error("Couldn't update your provider", { description: problemDetailOf(error) }),
	});
	const deleteConnection = useMutation({
		...filedUnder(connectionWriteKey, workspaceDeleteLlmConnectionMutation()),
		onSuccess: () => {
			void invalidateConnections();
			void invalidateModels();
			toast.success("Provider disconnected");
		},
		onError: (error) =>
			toast.error("Couldn't disconnect your provider", { description: problemDetailOf(error) }),
	});
	const probeConnection = useMutation({
		...filedUnder(probeKey, workspaceProbeLlmConnectionMutation()),
		onSuccess: (result, variables) => {
			setTestResults((current) =>
				new Map(current).set(
					variables.path.id,
					result.reachable
						? {
								ok: true,
								message: `Connected. ${result.modelCount} model${result.modelCount === 1 ? "" : "s"} available.`,
							}
						: { ok: false, message: result.message ?? "Could not reach the provider." },
				),
			);
		},
		onError: (error, variables) => {
			setTestResults((current) =>
				new Map(current).set(variables.path.id, {
					ok: false,
					message: problemDetailOf(error, "Could not reach the provider."),
				}),
			);
		},
	});
	const probingConnectionIds = usePendingMutationIds(probeKey, (variables) =>
		pathNumber(variables, "id"),
	);
	const writingConnectionIds = usePendingMutationIds(connectionWriteKey, (variables) =>
		pathNumber(variables, "id"),
	);

	const createModel = useMutation({
		...workspaceCreateLlmModelMutation(),
		onSuccess: () => {
			void invalidateModels();
			setModelDialogOpen(false);
			toast.success("Model added");
		},
		onError: (error) => {
			if (problemStatusOf(error) === 403) {
				setRegistrationDisabled(true);
			}
			toast.error("Couldn't add the model", { description: problemDetailOf(error) });
		},
	});
	const updateModel = useMutation({
		...filedUnder(modelWriteKey, workspaceUpdateLlmModelMutation()),
		onSuccess: () => {
			void invalidateModels();
			setModelDialogOpen(false);
			toast.success("Model updated");
		},
		onError: (error) =>
			toast.error("Couldn't update the model", { description: problemDetailOf(error) }),
	});
	const deleteModel = useMutation({
		...filedUnder(modelWriteKey, workspaceDeleteLlmModelMutation()),
		onSuccess: () => {
			void invalidateModels();
			toast.success("Model deleted");
		},
		onError: (error) =>
			toast.error("Couldn't delete the model", { description: problemDetailOf(error) }),
	});
	const mutatingModelIds = usePendingMutationIds(modelWriteKey, (variables) =>
		pathNumber(variables, "id"),
	);

	if (connectionsQuery.isError) {
		return (
			<QueryErrorAlert
				error={connectionsQuery.error}
				title="Could not load your AI providers"
				onRetry={() => void connectionsQuery.refetch()}
			/>
		);
	}
	if (connectionsQuery.isLoading) {
		return (
			<div className="flex h-32 items-center justify-center">
				<Spinner className="size-6" />
			</div>
		);
	}
	if (connections.length > 0 && modelsQuery.isError) {
		return (
			<QueryErrorAlert
				error={modelsQuery.error}
				title="Could not load your provider models"
				onRetry={() => void modelsQuery.refetch()}
			/>
		);
	}
	if (connections.length > 0 && modelsQuery.isLoading) {
		return (
			<div className="flex h-32 items-center justify-center">
				<Spinner className="size-6" />
			</div>
		);
	}

	const openCreateConnection = () => {
		setEditingConnection(null);
		setConnectionDialogOpen(true);
	};

	return (
		<div className="space-y-4">
			{registrationBlocked && (
				<Alert>
					<CircleAlert aria-hidden />
					<AlertTitle>New workspace providers and models are disabled</AlertTitle>
					<AlertDescription>
						An instance admin controls this setting. Providers and models you already have keep
						working, and you can still change them.
					</AlertDescription>
				</Alert>
			)}

			{connections.length === 0 ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<Plug />
						</EmptyMedia>
						<EmptyTitle>Connect an OpenAI-compatible provider</EmptyTitle>
						<EmptyDescription>
							The API key is encrypted and used only for this workspace. Usage is billed by the
							provider account that owns the key.
						</EmptyDescription>
					</EmptyHeader>
					{!registrationBlocked && (
						<Button onClick={openCreateConnection}>
							<Plus className="size-4" aria-hidden /> Connect provider
						</Button>
					)}
				</Empty>
			) : (
				<>
					<div className="flex flex-wrap items-center justify-between gap-3">
						<div className="min-w-0 flex-1">
							<h2 className="text-lg font-semibold">Your AI providers</h2>
						</div>
						{!registrationBlocked && (
							<Button size="sm" variant="outline" onClick={openCreateConnection}>
								<Plus className="size-4" aria-hidden /> Add provider
							</Button>
						)}
					</div>

					{connections.map((connection) => {
						const connectionModels = models.filter((model) => model.connectionId === connection.id);
						const testResult = testResults.get(connection.id);
						return (
							<Card
								key={connection.id}
								// A landmark, so a screen-reader user can reach one provider's card directly.
								role="region"
								aria-labelledby={`${cardLabelPrefix}-${connection.id}`}
							>
								<CardHeader>
									<div className="flex flex-wrap items-start justify-between gap-3">
										<div>
											<CardTitle id={`${cardLabelPrefix}-${connection.id}`}>
												{connection.displayName}
											</CardTitle>
											<CardDescription>
												{connection.hasApiKey
													? `Credential configured · ends in ····${connection.apiKeyLast4 ?? "····"}`
													: "No API key stored"}
											</CardDescription>
										</div>
										<Badge variant={connection.enabled ? "default" : "secondary"}>
											{connection.enabled ? "Active" : "Off"}
										</Badge>
									</div>
								</CardHeader>
								<CardContent className="space-y-4">
									{/* Repeated per card these would otherwise be identical names with nothing tying them
									    to a provider (SC 2.4.6); each opens with the button's own visible text so speech
									    control still matches (SC 2.5.3). */}
									<div className="flex flex-wrap gap-2">
										<Button
											variant="outline"
											size="sm"
											aria-label={`Edit ${connection.displayName}`}
											disabled={writingConnectionIds.has(connection.id)}
											onClick={() => {
												setEditingConnection(connection);
												setConnectionDialogOpen(true);
											}}
										>
											Edit
										</Button>
										<Button
											variant="outline"
											size="sm"
											aria-label={
												probingConnectionIds.has(connection.id)
													? `Testing… ${connection.displayName}`
													: `Test connection to ${connection.displayName}`
											}
											disabled={
												probingConnectionIds.has(connection.id) ||
												writingConnectionIds.has(connection.id)
											}
											onClick={() => {
												setTestResults((current) => {
													const next = new Map(current);
													next.delete(connection.id);
													return next;
												});
												probeConnection.mutate({ path: { workspaceSlug, id: connection.id } });
											}}
										>
											{probingConnectionIds.has(connection.id) ? "Testing…" : "Test connection"}
										</Button>
										<Button
											variant="outline"
											size="sm"
											className="text-destructive"
											aria-label={`Disconnect ${connection.displayName}`}
											disabled={writingConnectionIds.has(connection.id)}
											onClick={() => setDeletingConnection(connection)}
										>
											Disconnect
										</Button>
									</div>
									{testResult && (
										// An assertive `alert` on success would cut across whatever is being read
										// (SC 4.1.3); a failure still earns it.
										<Alert
											variant={testResult.ok ? "success" : "destructive"}
											role={testResult.ok ? "status" : "alert"}
										>
											<AlertDescription>{testResult.message}</AlertDescription>
										</Alert>
									)}
									<div className="space-y-3">
										<div className="flex items-center justify-between">
											{/* `CardTitle` is a `<div>`, so an `h4` here would skip a level (SC 1.3.1). */}
											<h3 className="text-sm font-medium">Models</h3>
											{!registrationBlocked && (
												<Button
													size="sm"
													variant="outline"
													onClick={() => {
														setEditingModel(null);
														setModelConnectionId(connection.id);
														setModelDialogOpen(true);
													}}
												>
													<Plus className="size-4" aria-hidden /> Add model
												</Button>
											)}
										</div>
										<WorkspaceLlmModelsTable
											models={connectionModels}
											mutatingIds={mutatingModelIds}
											onEdit={(model) => {
												setEditingModel(model);
												setModelConnectionId(model.connectionId);
												setModelDialogOpen(true);
											}}
											onDelete={(model) =>
												deleteModel.mutate({ path: { workspaceSlug, id: model.id } })
											}
										/>
									</div>
								</CardContent>
							</Card>
						);
					})}
				</>
			)}

			<WorkspaceLlmConnectionFormDialog
				open={connectionDialogOpen}
				onOpenChange={setConnectionDialogOpen}
				editing={editingConnection}
				isSubmitting={createConnection.isPending || updateConnection.isPending}
				onCreate={(body: CreateWorkspaceLlmConnectionRequest) =>
					createConnection.mutate({ path: { workspaceSlug }, body })
				}
				onUpdate={(id, body: UpdateWorkspaceLlmConnectionRequest) =>
					updateConnection.mutate({ path: { workspaceSlug, id }, body })
				}
			/>
			<WorkspaceLlmModelFormDialog
				open={modelDialogOpen}
				onOpenChange={setModelDialogOpen}
				editing={editingModel}
				isSubmitting={createModel.isPending || updateModel.isPending}
				onCreate={(body: CreateWorkspaceLlmModelRequest) => {
					if (modelConnectionId == null) {
						return;
					}
					createModel.mutate({ path: { workspaceSlug, connectionId: modelConnectionId }, body });
				}}
				onUpdate={(id, body: UpdateWorkspaceLlmModelRequest) =>
					updateModel.mutate({ path: { workspaceSlug, id }, body })
				}
			/>

			<ConfirmDialog
				subject={deletingConnection}
				onClose={() => setDeletingConnection(null)}
				title={(connection) => `Disconnect “${connection.displayName}”?`}
				description="The stored credential will be permanently removed. A connection with models still on it cannot be disconnected."
				confirmLabel="Disconnect provider"
				onConfirm={(connection) =>
					deleteConnection.mutate({ path: { workspaceSlug, id: connection.id } })
				}
			/>
		</div>
	);
}
