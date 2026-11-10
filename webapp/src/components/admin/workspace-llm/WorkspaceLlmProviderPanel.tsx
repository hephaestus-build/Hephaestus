import { CircleAlert, Plug, Plus } from "lucide-react";
import { useId, useState } from "react";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import type {
	WorkspaceLlmConnection,
	WorkspaceLlmModel,
	WorkspaceLlmProbeResult,
} from "@/api/types.gen";
import { LlmConnectionApi } from "@/components/admin/instance-llm/LlmConnectionApi";
import { apiKeyLine } from "@/components/admin/instance-llm/LlmConnectionFields";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { Section } from "@/components/layout/Section";
import { MODEL_READINESS_DEFS } from "@/components/practice-vocabulary/model-readiness-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

import { WorkspaceLlmModelsTable } from "./WorkspaceLlmModelsTable";

export type ProviderTestResult =
	| { status: "answered"; result: WorkspaceLlmProbeResult }
	| { status: "failed"; message: string };

export type ProviderPanelState = PanelState<{
	connections: WorkspaceLlmConnection[];
	models: WorkspaceLlmModel[];
	refreshFailure?: RefreshFailure;
}>;

export interface WorkspaceLlmProviderPanelProps {
	state: ProviderPanelState;
	/**
	 * Whether this workspace may register *new* providers and models now. False does not hide the
	 * panel: providers already connected stay listed and editable.
	 */
	registrationAllowed: boolean;
	testResults: ReadonlyMap<number, ProviderTestResult>;
	testingConnectionIds: ReadonlySet<number>;
	writingConnectionIds: ReadonlySet<number>;
	writingModelIds: ReadonlySet<number>;
	/** A new provider is being saved; Add waits for it, so a dismissed form cannot add it twice. */
	addingConnection?: boolean;
	/** Providers with a new model being saved; their Add model waits for it. */
	addingModelConnectionIds?: ReadonlySet<number>;
	onAddConnection: () => void;
	onEditConnection: (connection: WorkspaceLlmConnection) => void;
	onTestConnection: (connection: WorkspaceLlmConnection) => void;
	onDisconnect: (connection: WorkspaceLlmConnection) => void;
	onAddModel: (connection: WorkspaceLlmConnection) => void;
	onEditModel: (model: WorkspaceLlmModel) => void;
	onDeleteModel: (model: WorkspaceLlmModel) => void;
}

interface TestOutcome {
	tone: "ok" | "failed";
	message: string;
}

function testOutcome(test: ProviderTestResult): TestOutcome {
	if (test.status === "failed") {
		return { tone: "failed", message: test.message };
	}
	const { result } = test;
	if (result.reachable) {
		const count = result.modelCount;
		return { tone: "ok", message: `Connected. ${count} model${count === 1 ? "" : "s"} available.` };
	}
	return { tone: "failed", message: result.message ?? "We could not reach the provider." };
}

const NO_IDS: ReadonlySet<number> = new Set();

const TITLE = "Your providers";
const DESCRIPTION =
	"Connect a provider account to run models on your own key. Its usage is billed to that account.";

function ProviderPanelSkeleton() {
	return (
		<div className="space-y-3" aria-busy="true">
			<div className="space-y-1.5">
				<Skeleton className="h-5 w-48" />
				<Skeleton className="h-4 w-64" />
			</div>
			<Skeleton className="h-24 w-full" />
		</div>
	);
}

export function WorkspaceLlmProviderPanel({
	state,
	registrationAllowed,
	testResults,
	testingConnectionIds,
	writingConnectionIds,
	writingModelIds,
	addingConnection = false,
	addingModelConnectionIds = NO_IDS,
	onAddConnection,
	onEditConnection,
	onTestConnection,
	onDisconnect,
	onAddModel,
	onEditModel,
	onDeleteModel,
}: WorkspaceLlmProviderPanelProps) {
	const [disconnecting, setDisconnecting] = useState<WorkspaceLlmConnection | null>(null);
	if (state.status === "error" && disconnecting !== null) {
		setDisconnecting(null);
	}
	const connections = state.status === "ready" ? state.connections : [];

	return (
		<Section
			title={TITLE}
			description={DESCRIPTION}
			className="space-y-6"
			actions={
				registrationAllowed && connections.length > 0 ? (
					<Button size="sm" variant="outline" disabled={addingConnection} onClick={onAddConnection}>
						<Plus className="size-4" aria-hidden /> Add provider
					</Button>
				) : undefined
			}
		>
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load your AI providers"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "loading" && <ProviderPanelSkeleton />}
			{state.status === "ready" && (
				<div className="space-y-8">
					{state.refreshFailure !== undefined && (
						<QueryErrorAlert
							error={state.refreshFailure.error}
							title="We could not refresh your AI providers"
							pending={state.refreshFailure.pending}
							onRetry={state.refreshFailure.onRetry}
						/>
					)}
					{!registrationAllowed && (
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
								<EmptyTitle>Connect your own provider</EmptyTitle>
								<EmptyDescription>
									The API key is encrypted and used only for this workspace. Usage is billed by the
									provider account that owns the key.
								</EmptyDescription>
							</EmptyHeader>
							{registrationAllowed && (
								<Button disabled={addingConnection} onClick={onAddConnection}>
									<Plus className="size-4" aria-hidden /> Add provider
								</Button>
							)}
						</Empty>
					) : (
						connections.map((connection) => (
							<ProviderSection
								key={connection.id}
								connection={connection}
								models={state.models.filter((model) => model.connectionId === connection.id)}
								registrationAllowed={registrationAllowed}
								test={testResults.get(connection.id)}
								testing={testingConnectionIds.has(connection.id)}
								writing={writingConnectionIds.has(connection.id)}
								writingModelIds={writingModelIds}
								addingModel={addingModelConnectionIds.has(connection.id)}
								onEdit={() => onEditConnection(connection)}
								onTest={() => onTestConnection(connection)}
								onDisconnect={() => setDisconnecting(connection)}
								onAddModel={() => onAddModel(connection)}
								onEditModel={onEditModel}
								onDeleteModel={onDeleteModel}
							/>
						))
					)}
				</div>
			)}

			<ConfirmDialog
				subject={disconnecting}
				onClose={() => setDisconnecting(null)}
				title={(connection) => `Disconnect “${connection.displayName}”?`}
				description="The stored credential will be permanently removed. You cannot undo this."
				confirmLabel="Disconnect provider"
				onConfirm={onDisconnect}
			/>
		</Section>
	);
}

interface ProviderSectionProps {
	connection: WorkspaceLlmConnection;
	models: WorkspaceLlmModel[];
	registrationAllowed: boolean;
	test: ProviderTestResult | undefined;
	testing: boolean;
	writing: boolean;
	writingModelIds: ReadonlySet<number>;
	addingModel: boolean;
	onEdit: () => void;
	onTest: () => void;
	onDisconnect: () => void;
	onAddModel: () => void;
	onEditModel: (model: WorkspaceLlmModel) => void;
	onDeleteModel: (model: WorkspaceLlmModel) => void;
}

function ProviderSection({
	connection,
	models,
	registrationAllowed,
	test,
	testing,
	writing,
	writingModelIds,
	addingModel,
	onEdit,
	onTest,
	onDisconnect,
	onAddModel,
	onEditModel,
	onDeleteModel,
}: ProviderSectionProps) {
	const outcome = test && testOutcome(test);
	const keepsModels = models.length > 0;
	const keepsModelsId = useId();
	return (
		<Section
			level={3}
			size="sm"
			title={connection.displayName}
			description={
				<span className="flex flex-wrap items-center gap-x-4 gap-y-1">
					<LlmConnectionApi connection={connection} />
					<span>{apiKeyLine(connection)}</span>
				</span>
			}
			// Accessible action names must identify the provider and begin with the visible button label (WCAG 2.4.6, 2.5.3).
			actions={
				<div className="flex flex-col gap-1 sm:items-end">
					<div className="flex flex-wrap items-center gap-2">
						{!connection.enabled && <StatusBadge def={MODEL_READINESS_DEFS.OFF} />}
						<Button
							variant="outline"
							size="sm"
							aria-label={`Edit ${connection.displayName}`}
							disabled={writing}
							onClick={onEdit}
						>
							Edit
						</Button>
						<Button
							variant="outline"
							size="sm"
							aria-label={
								testing
									? `Testing… ${connection.displayName}`
									: `Test connection to ${connection.displayName}`
							}
							disabled={testing || writing}
							onClick={onTest}
						>
							{testing ? "Testing…" : "Test connection"}
						</Button>
						<Button
							variant="destructive-outline"
							size="sm"
							aria-label={`Disconnect ${connection.displayName}`}
							aria-describedby={keepsModels ? keepsModelsId : undefined}
							disabled={writing || keepsModels}
							onClick={onDisconnect}
						>
							Disconnect
						</Button>
						{registrationAllowed && (
							<Button
								variant="outline"
								size="sm"
								aria-label={`Add model to ${connection.displayName}`}
								disabled={addingModel}
								onClick={onAddModel}
							>
								<Plus className="size-4" aria-hidden /> Add model
							</Button>
						)}
					</div>
					{keepsModels && (
						<p id={keepsModelsId} className="text-xs text-muted-foreground">
							To disconnect, delete its models first.
						</p>
					)}
				</div>
			}
		>
			{/* Mounted empty, so a result is announced when it arrives (ARIA22). Polite: an assertive
			    one would cut across whatever is being read (SC 4.1.3). */}
			<div role="status" className="empty:hidden">
				{outcome?.tone === "ok" && (
					<Alert variant="success" role="none">
						<AlertDescription>{outcome.message}</AlertDescription>
					</Alert>
				)}
			</div>
			{/* A failure still earns an assertive alert. */}
			{outcome?.tone === "failed" && (
				<Alert variant="destructive">
					<AlertDescription>{outcome.message}</AlertDescription>
				</Alert>
			)}
			<WorkspaceLlmModelsTable
				providerName={connection.displayName}
				connectionEnabled={connection.enabled}
				models={models}
				mutatingIds={writingModelIds}
				onEdit={onEditModel}
				onDelete={onDeleteModel}
			/>
		</Section>
	);
}
