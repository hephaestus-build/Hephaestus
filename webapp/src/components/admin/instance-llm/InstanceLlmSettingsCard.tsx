import { useState } from "react";

import type { InstanceLlmSettings, UpdateInstanceLlmSettingsRequest } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";

export interface InstanceLlmSettingsCardProps {
	settings?: InstanceLlmSettings;
	isLoading: boolean;
	isSubmitting: boolean;
	onSave: (body: UpdateInstanceLlmSettingsRequest) => void;
}

/**
 * The form is mounted only once its settings exist, so its fields seed from them directly. An effect
 * copying settings into state would re-run on a background refetch and overwrite an unsaved edit.
 */
export function InstanceLlmSettingsCard({
	settings,
	isLoading,
	isSubmitting,
	onSave,
}: InstanceLlmSettingsCardProps) {
	if (isLoading || !settings) {
		return (
			<Card>
				<CardHeader>
					<CardTitle>Settings</CardTitle>
				</CardHeader>
			</Card>
		);
	}

	return (
		<InstanceLlmSettingsForm settings={settings} isSubmitting={isSubmitting} onSave={onSave} />
	);
}

interface InstanceLlmSettingsFormProps {
	settings: InstanceLlmSettings;
	isSubmitting: boolean;
	onSave: (body: UpdateInstanceLlmSettingsRequest) => void;
}

function InstanceLlmSettingsForm({ settings, isSubmitting, onSave }: InstanceLlmSettingsFormProps) {
	const [allowedHosts, setAllowedHosts] = useState(settings.allowedEgressHosts ?? "");
	const [allowWorkspaceConnections, setAllowWorkspaceConnections] = useState(
		settings.allowWorkspaceConnections,
	);

	// Compared trimmed, because that is the form the payload is sent in.
	const savedHosts = (settings.allowedEgressHosts ?? "").trim();
	const dirty =
		allowedHosts.trim() !== savedHosts ||
		allowWorkspaceConnections !== settings.allowWorkspaceConnections;

	return (
		<Card>
			<CardHeader>
				<CardTitle>Settings</CardTitle>
				<CardDescription>Instance-wide rules that apply to every workspace.</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				<Field>
					<FieldLabel htmlFor="llm-settings-allowed-hosts">Allowed provider hosts</FieldLabel>
					<Textarea
						id="llm-settings-allowed-hosts"
						value={allowedHosts}
						onChange={(e) => setAllowedHosts(e.target.value)}
						placeholder="api.openai.com&#10;llm.example.com"
						rows={4}
					/>
					<FieldDescription>
						One host per line (or comma-separated). Blank allows any public host.
					</FieldDescription>
				</Field>

				<Field orientation="horizontal">
					<FieldContent>
						<FieldLabel htmlFor="llm-settings-allow-own-provider">
							Let workspaces add providers and models
						</FieldLabel>
						<FieldDescription>
							Controls new provider connections and new models. Existing providers and models remain
							manageable and are billed to the account that owns their credential.
						</FieldDescription>
					</FieldContent>
					<Switch
						id="llm-settings-allow-own-provider"
						checked={allowWorkspaceConnections}
						onCheckedChange={setAllowWorkspaceConnections}
					/>
				</Field>

				<div className="flex justify-end">
					<Button
						size="sm"
						disabled={!dirty || isSubmitting}
						onClick={() =>
							onSave({
								allowedEgressHosts: allowedHosts.trim(),
								allowWorkspaceConnections,
							})
						}
					>
						Save settings
					</Button>
				</div>
			</CardContent>
		</Card>
	);
}
