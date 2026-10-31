import { AlertCircle } from "lucide-react";
import type { PracticeReviewSettings, UpdatePracticeReviewSettingsRequest } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { WORKSPACE_DELIVERY_STATUS_DEFS } from "@/components/practice-vocabulary/workspace-delivery-status-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import { InheritedSettingHint } from "./InheritedSettingHint";

export type FeedbackSendingUpdate = Pick<
	UpdatePracticeReviewSettingsRequest,
	"deliveryStatus" | "deliverToMerged"
>;

export interface FeedbackSendingSettingsProps {
	settings: Pick<
		PracticeReviewSettings,
		"deliveryStatus" | "deliverToMerged" | "deliverToMergedOverride"
	>;
	saving: boolean;
	onSet: (update: FeedbackSendingUpdate) => void;
	onClearDeliverToMerged: () => void;
}

export function FeedbackSendingSettings({
	settings,
	saving,
	onSet,
	onClearDeliverToMerged,
}: FeedbackSendingSettingsProps) {
	const paused = settings.deliveryStatus === "PAUSED";

	return (
		<Card>
			<CardHeader>
				<CardTitle>
					{/* The id is the anchor that a link to this block names. */}
					<h2 id="feedback-delivery-heading">Sending feedback</h2>
				</CardTitle>
				<CardDescription>
					These settings apply to all comments and feedback from Hephaestus in the workspace. Each
					practice’s feedback mode still decides whether its feedback waits for approval.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
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
							Pause all comments and feedback from Hephaestus without stopping reviews or changing
							the review scope.
						</FieldDescription>
					</FieldContent>
					<Switch
						id="policy-delivery-active"
						checked={!paused}
						disabled={saving}
						onCheckedChange={(checked) => onSet({ deliveryStatus: checked ? "ACTIVE" : "PAUSED" })}
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
							inheritedValue={settings.deliverToMerged ? "On" : "Off"}
							disabled={saving}
							onReset={onClearDeliverToMerged}
						/>
					</FieldContent>
					<Switch
						id="policy-deliver-merged"
						checked={settings.deliverToMerged}
						disabled={saving}
						onCheckedChange={(checked) => onSet({ deliverToMerged: checked })}
					/>
				</Field>
			</CardContent>
		</Card>
	);
}
