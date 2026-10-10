import { CircleAlert, Plug, Plus } from "lucide-react";
import { useId, useState } from "react";

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
import { listsNoModels, UNLISTED_MODELS } from "@/lib/llm-api-protocol-labels";

import { WorkspaceLlmModelsTable } from "./WorkspaceLlmModelsTable";

/** What the last test of one connection came back with. */
export type ProviderTestResult =
	| { status: "answered"; result: WorkspaceLlmProbeResult }
	| { status: "failed"; message: string };

export type ProviderPanelState = PanelState<{
	connections: WorkspaceLlmConnection[];
	models: WorkspaceLlmModel[];
}>;

export interface WorkspaceLlmProviderPanelProps {
	state: ProviderPanelState;
	/**
	 * Whether this workspace may register *new* providers and models now. False does not hide the
	 * panel: providers already connected stay listed and editable.
	 */
	registrationAllowed: boolean;
	/** The last test of each connection, by connection id. */
	testResults: ReadonlyMap<number, ProviderTestResult>;
	/** Connections with a test in flight. */
	testingConnectionIds: ReadonlySet<number>;
	/** Connections with an edit or a disconnect in flight. */
	writingConnectionIds: ReadonlySet<number>;
	/** Models with an edit or a delete in flight. */
	writingModelIds: ReadonlySet<number>;
	onAddConnection: () => void;
	onEditConnection: (connection: WorkspaceLlmConnection) => void;
	onTestConnection: (connection: WorkspaceLlmConnection) => void;
	/** Called once the admin confirmed. */
	onDisconnect: (connection: WorkspaceLlmConnection) => void;
	onAddModel: (connection: WorkspaceLlmConnection) => void;
	onEditModel: (model: WorkspaceLlmModel) => void;
	onDeleteModel: (model: WorkspaceLlmModel) => void;
}

interface TestOutcome {
	/** `neutral`: a precompute endpoint may not list its models at all, so its failed probe is no fault. */
	tone: "ok" | "neutral" | "failed";
	message: string;
}

const TEST_OUTCOME_VARIANT = {
	ok: "success",
	neutral: "default",
	failed: "destructive",
} as const satisfies Record<TestOutcome["tone"], "success" | "default" | "destructive">;

function testOutcome(test: ProviderTestResult, connection: WorkspaceLlmConnection): TestOutcome {
	if (test.status === "failed") {
		return { tone: "failed", message: test.message };
	}
	const { result } = test;
	if (result.reachable) {
		const count = result.modelCount;
		return { tone: "ok", message: `Connected. ${count} model${count === 1 ? "" : "s"} available.` };
	}
	if (listsNoModels(connection.apiProtocol, result)) {
		return { tone: "neutral", message: UNLISTED_MODELS };
	}
	return { tone: "failed", message: result.message ?? "We could not reach the provider." };
}

const TITLE = "Your providers";
const DESCRIPTION =
	"Connect a provider account to run models on your own key. Its usage is billed to that account.";

/** One provider's shape while the providers load: its heading, its facts and its models table. */
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

/**
 * The workspace's own AI providers and their models, on AI models below the assignments. One
 * section, each provider a heading with its facts and actions, and its models one bordered table:
 * no card around a table around an empty box.
 */
export function WorkspaceLlmProviderPanel({
	state,
	registrationAllowed,
	testResults,
	testingConnectionIds,
	writingConnectionIds,
	writingModelIds,
	onAddConnection,
	onEditConnection,
	onTestConnection,
	onDisconnect,
	onAddModel,
	onEditModel,
	onDeleteModel,
}: WorkspaceLlmProviderPanelProps) {
	const [disconnecting, setDisconnecting] = useState<WorkspaceLlmConnection | null>(null);
	const connections = state.status === "ready" ? state.connections : [];

	return (
		<Section
			title={TITLE}
			description={DESCRIPTION}
			className="space-y-6"
			// With no provider yet, the empty state below holds the one way to add one.
			actions={
				registrationAllowed && connections.length > 0 ? (
					<Button size="sm" variant="outline" onClick={onAddConnection}>
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
								<Button onClick={onAddConnection}>
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
	onEdit,
	onTest,
	onDisconnect,
	onAddModel,
	onEditModel,
	onDeleteModel,
}: ProviderSectionProps) {
	const outcome = test && testOutcome(test, connection);
	// The server refuses to disconnect a provider that still has models.
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
			// Repeated per provider these would otherwise be identical names with nothing tying them to
			// one (SC 2.4.6); each opens with the button's own visible text so speech control still
			// matches (SC 2.5.3).
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
				{outcome !== undefined && outcome.tone !== "failed" && (
					<Alert variant={TEST_OUTCOME_VARIANT[outcome.tone]} role="none">
						<AlertDescription>{outcome.message}</AlertDescription>
					</Alert>
				)}
			</div>
			{/* A failure still earns an assertive alert. */}
			{outcome?.tone === "failed" && (
				<Alert variant={TEST_OUTCOME_VARIANT.failed}>
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
