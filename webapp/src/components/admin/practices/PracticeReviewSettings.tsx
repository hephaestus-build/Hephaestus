import { Link } from "@tanstack/react-router";
import { cn } from "cn";
import { AlertCircle } from "lucide-react";
import { useState } from "react";
import { z } from "zod";
import type {
	AgentBinding,
	PracticeReviewSettings as PracticeReviewSettingsData,
	UpdatePracticeReviewSettingsRequest,
	UpdateWorkspaceFeaturesRequest,
} from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { WORKSPACE_DELIVERY_STATUS_DEFS } from "@/components/practice-vocabulary/workspace-delivery-status-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { GeneratedPathsSettings } from "./GeneratedPathsSettings";
import {
	PracticeReviewScopeSettings,
	type PracticeReviewScopeSettingsProps,
} from "./PracticeReviewScopeSettings";
import { reviewModelRunnable } from "./review/review-readiness";

export type PracticeReviewField = NonNullable<UpdatePracticeReviewSettingsRequest["reset"]>[number];
export type PracticeReviewWorkspaceUpdate = Pick<
	UpdateWorkspaceFeaturesRequest,
	"practicesEnabled" | "practiceReviewAutoTriggerEnabled" | "practiceReviewManualTriggerEnabled"
>;

export interface PracticeReviewSettingsProps {
	workspaceSlug: string;
	model:
		| { status: "loading" }
		| { status: "error"; onRetry: () => void }
		| { status: "ready"; binding?: AgentBinding };
	workspace: {
		enabled: boolean;
		startsFromActivity: boolean;
		startsOnRequest: boolean;
		isSaving: boolean;
		onUpdate: (settings: PracticeReviewWorkspaceUpdate) => void;
	};
	policy: {
		settings: PracticeReviewSettingsData;
		isSaving: boolean;
		onUpdate: (settings: UpdatePracticeReviewSettingsRequest, sourceEtag?: string) => Promise<void>;
		onReset: (field: PracticeReviewField) => void;
	};
	scope: Pick<PracticeReviewScopeSettingsProps, "preview" | "repositories" | "people">;
}

export function PracticeReviewSettings({
	workspaceSlug,
	model,
	workspace,
	policy,
	scope,
}: PracticeReviewSettingsProps) {
	return (
		<div className="space-y-8">
			<ReviewStatusSection workspaceSlug={workspaceSlug} model={model} workspace={workspace} />
			<ReviewTimingSection workspace={workspace} policy={policy} />
			<PracticeReviewScopeSettings
				settings={policy.settings}
				preview={scope.preview}
				onSave={async (reviewScope, sourceEtag) => policy.onUpdate({ reviewScope }, sourceEtag)}
				repositories={scope.repositories}
				people={scope.people}
			/>
			<GeneratedPathsSettings
				settings={policy.settings}
				repositories={scope.repositories}
				isSaving={policy.isSaving}
				onSave={policy.onUpdate}
			/>
			<FeedbackDeliverySection policy={policy} />
		</div>
	);
}

function ReviewStatusSection({
	workspaceSlug,
	model,
	workspace,
}: Pick<PracticeReviewSettingsProps, "workspaceSlug" | "model" | "workspace">) {
	const modelRunnable = reviewModelRunnable(model);
	const modelUnavailable = model.status !== "ready" || !modelRunnable;

	return (
		<section className="space-y-4" aria-labelledby="review-status-heading">
			<div className="space-y-1">
				<h2 id="review-status-heading" className="text-lg font-semibold">
					Automated review
				</h2>
				<p className="text-sm text-muted-foreground">
					Hephaestus reviews new work against every practice it reviews automatically. Practices
					reviewed by people or by nobody are left out. Feedback mode decides separately where
					observations become feedback.
				</p>
			</div>
			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="practice-reviews-enabled">Start practice reviews</FieldLabel>
					<FieldDescription>
						{!workspace.enabled && modelUnavailable
							? "This can be turned on once a review model is ready to run."
							: "New work is reviewed while this is on. Turning it off stops new reviews. Any already running may finish."}
					</FieldDescription>
				</FieldContent>
				<Switch
					id="practice-reviews-enabled"
					checked={workspace.enabled}
					disabled={workspace.isSaving || (!workspace.enabled && modelUnavailable)}
					onCheckedChange={(checked) => workspace.onUpdate({ practicesEnabled: checked })}
				/>
			</Field>
			<ModelReadiness workspaceSlug={workspaceSlug} model={model} runnable={modelRunnable} />
		</section>
	);
}

function ModelReadiness({
	workspaceSlug,
	model,
	runnable,
}: Pick<PracticeReviewSettingsProps, "workspaceSlug" | "model"> & { runnable: boolean }) {
	if (model.status === "loading") {
		return null;
	}

	if (model.status === "error") {
		return (
			<Alert variant="warning" role="status">
				<AlertCircle />
				<AlertTitle>We could not check the review model</AlertTitle>
				<AlertDescription>
					<Button variant="outline" size="sm" onClick={model.onRetry}>
						Retry
					</Button>
				</AlertDescription>
			</Alert>
		);
	}

	if (runnable) {
		return (
			<Link
				to="/w/$workspaceSlug/admin/models"
				params={{ workspaceSlug }}
				className={cn(buttonVariants({ variant: "link", size: "sm" }), "h-auto self-start p-0")}
			>
				Change the review model
			</Link>
		);
	}

	return (
		<Alert variant="warning" role="status">
			<AlertCircle />
			<AlertTitle>
				{model.binding ? "The review model is unavailable" : "No review model selected"}
			</AlertTitle>
			<AlertDescription>
				<p>
					{model.binding
						? "The selected model is turned off or was removed, so reviews cannot run."
						: "Reviews cannot run until a model is chosen."}
				</p>
				<Link
					to="/w/$workspaceSlug/admin/models"
					params={{ workspaceSlug }}
					className={cn(buttonVariants({ variant: "outline", size: "sm" }), "mt-2")}
				>
					Choose a review model
				</Link>
			</AlertDescription>
		</Alert>
	);
}

function ReviewTimingSection({
	workspace,
	policy,
}: Pick<PracticeReviewSettingsProps, "workspace" | "policy">) {
	const noWayIn = workspace.enabled && !workspace.startsFromActivity && !workspace.startsOnRequest;

	return (
		<section className="space-y-4" aria-labelledby="review-timing-heading">
			<div className="space-y-1">
				<h2 id="review-timing-heading" className="text-lg font-semibold">
					How reviews start
				</h2>
				<p className="text-sm text-muted-foreground">
					Reviews start from activity on connected work or from a request.
				</p>
			</div>
			{noWayIn ? (
				<Alert variant="warning" role="status">
					<AlertCircle />
					<AlertTitle>Nothing can start a review</AlertTitle>
					<AlertDescription>
						Practice reviews are on, but both ways to start one are turned off.
					</AlertDescription>
				</Alert>
			) : null}
			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="start-from-activity">Start from work activity</FieldLabel>
					<FieldDescription>
						A review starts when connected work reaches an event a practice reviews on, such as
						opened, merged, or published.
					</FieldDescription>
				</FieldContent>
				<Switch
					id="start-from-activity"
					checked={workspace.startsFromActivity}
					disabled={workspace.isSaving}
					onCheckedChange={(checked) =>
						workspace.onUpdate({ practiceReviewAutoTriggerEnabled: checked })
					}
				/>
			</Field>
			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="start-on-request">Start requested reviews</FieldLabel>
					<FieldDescription>
						Covers the <strong>Request review</strong> button, reviews of past work, recurring
						checks, and <code>/hephaestus review</code> in a GitLab merge request comment. Turning
						this off stops all of them.
					</FieldDescription>
				</FieldContent>
				<Switch
					id="start-on-request"
					checked={workspace.startsOnRequest}
					disabled={workspace.isSaving}
					onCheckedChange={(checked) =>
						workspace.onUpdate({ practiceReviewManualTriggerEnabled: checked })
					}
				/>
			</Field>
			<CooldownField
				key={policy.settings.cooldownMinutes}
				value={policy.settings.cooldownMinutes}
				overridden={policy.settings.cooldownMinutesOverride != null}
				policy={policy}
			/>
		</section>
	);
}

const cooldownSchema = z.coerce.number().int().min(0).max(1440);

function CooldownField({
	value,
	overridden,
	policy,
}: {
	value: number;
	overridden: boolean;
	policy: PracticeReviewSettingsProps["policy"];
}) {
	const [draft, setDraft] = useState(String(value));
	const parsed = cooldownSchema.safeParse(draft.trim() === "" ? undefined : draft);
	const invalid = !parsed.success;

	return (
		<Field data-invalid={invalid || undefined}>
			<FieldLabel htmlFor="policy-cooldown">Time between reviews (minutes)</FieldLabel>
			<Input
				id="policy-cooldown"
				name="practice-review-cooldown"
				type="number"
				autoComplete="off"
				min={0}
				max={1440}
				step={1}
				value={draft}
				aria-invalid={invalid || undefined}
				aria-describedby={invalid ? "policy-cooldown-error" : undefined}
				disabled={policy.isSaving}
				onChange={(event) => setDraft(event.currentTarget.value)}
				onBlur={() => {
					if (parsed.success && parsed.data !== value) {
						void policy.onUpdate({ cooldownMinutes: parsed.data });
					}
				}}
				className="max-w-32"
			/>
			<FieldDescription>
				Minimum time between reviews of the same pull or merge request, from 0 to 1,440 minutes.
			</FieldDescription>
			{invalid && (
				<FieldError id="policy-cooldown-error">Enter a whole number from 0 to 1,440.</FieldError>
			)}
			<InheritedSettingHint
				label="Time between reviews"
				overridden={overridden}
				field="COOLDOWN_MINUTES"
				inheritedValue={`${value} min`}
				policy={policy}
			/>
		</Field>
	);
}

function FeedbackDeliverySection({ policy }: Pick<PracticeReviewSettingsProps, "policy">) {
	const { settings } = policy;
	const paused = settings.deliveryStatus === "PAUSED";

	return (
		<section className="space-y-4" aria-labelledby="feedback-delivery-heading">
			<div className="space-y-1">
				<h2 id="feedback-delivery-heading" className="text-lg font-semibold">
					Sending feedback
				</h2>
				<p className="text-sm text-muted-foreground">
					These settings apply to all comments and feedback from Hephaestus in the workspace. Each
					practice’s feedback mode still decides whether its feedback waits for approval.
				</p>
			</div>
			{paused ? (
				<Alert variant="warning" role="status">
					<AlertCircle />
					<AlertTitle>Sending is paused</AlertTitle>
					<AlertDescription>
						Reviews and in-app feedback continue, but no comments or feedback leave Hephaestus.
						Automatic feedback is not queued for later delivery. Pending approvals remain in the
						review queue, and a workspace owner or admin can decide them after resuming.
					</AlertDescription>
				</Alert>
			) : null}
			<Field orientation="horizontal">
				<FieldContent>
					<div className="flex flex-wrap items-center gap-2">
						<FieldLabel htmlFor="policy-delivery-active">Send feedback</FieldLabel>
						<StatusBadge def={WORKSPACE_DELIVERY_STATUS_DEFS[settings.deliveryStatus]} />
					</div>
					<FieldDescription>
						Pause all comments and feedback from Hephaestus without stopping reviews or changing the
						review scope.
					</FieldDescription>
				</FieldContent>
				<Switch
					id="policy-delivery-active"
					checked={!paused}
					disabled={policy.isSaving}
					onCheckedChange={(checked) => {
						void policy.onUpdate({ deliveryStatus: checked ? "ACTIVE" : "PAUSED" });
					}}
				/>
			</Field>
			<Field orientation="horizontal">
				<FieldContent>
					<FieldLabel htmlFor="policy-deliver-merged">Post feedback after merge</FieldLabel>
					<FieldDescription>
						When on, a review that finishes after the work merged still posts its feedback.
					</FieldDescription>
					<InheritedSettingHint
						label="Post feedback after merge"
						overridden={settings.deliverToMergedOverride != null}
						field="DELIVER_TO_MERGED"
						inheritedValue={settings.deliverToMerged ? "On" : "Off"}
						policy={policy}
					/>
				</FieldContent>
				<Switch
					id="policy-deliver-merged"
					checked={settings.deliverToMerged}
					disabled={policy.isSaving}
					onCheckedChange={(checked) => {
						void policy.onUpdate({ deliverToMerged: checked });
					}}
				/>
			</Field>
		</section>
	);
}

function InheritedSettingHint({
	label,
	overridden,
	field,
	inheritedValue,
	policy,
}: {
	label: string;
	overridden: boolean;
	field: PracticeReviewField;
	inheritedValue: string;
	policy: PracticeReviewSettingsProps["policy"];
}) {
	if (!overridden) {
		return (
			<span className="self-start text-xs text-muted-foreground">Default: {inheritedValue}</span>
		);
	}

	return (
		<div className="text-left">
			<Button
				variant="link"
				size="inline"
				className="text-xs"
				aria-label={`Use default for ${label}`}
				disabled={policy.isSaving}
				onClick={() => policy.onReset(field)}
			>
				Use default
			</Button>
		</div>
	);
}
