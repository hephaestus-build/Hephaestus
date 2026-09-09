import { useId, useState } from "react";

import type {
	ConfigureWorkspaceAccessPolicy,
	IdentityProviderView,
	TeamInfo,
	WorkspaceAccessPolicy,
	WorkspaceAccessPolicySettings,
} from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel, FieldSet, FieldLegend } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

interface WorkspaceAccessPolicyFormProps {
	policy: WorkspaceAccessPolicy;
	providers: IdentityProviderView[];
	teams: TeamInfo[];
	pending: boolean;
	error?: string;
	onSave: (policy: ConfigureWorkspaceAccessPolicy) => void;
}

/** The route keys the editor by saved version; drafts stay local until the owner saves. */
export function WorkspaceAccessPolicyForm({
	policy,
	providers,
	teams,
	pending,
	error,
	onSave,
}: WorkspaceAccessPolicyFormProps) {
	const id = useId();
	const [enabled, setEnabled] = useState(policy.enabled ?? false);
	const [settings, setSettings] = useState<WorkspaceAccessPolicySettings>(
		policy.settings ?? {
			primaryRegistrationId: "",
			introductionMarkdown: "",
			acknowledgementLabel: "I have read and agree to the workspace code of conduct",
			maintainerTeamId: 0,
			requestableTeamIds: [],
			requiredLinks: [],
			notices: [],
			maximumDurationDays: 90,
			reminderDays: 14,
			adminMailbox: "",
		},
	);
	const selectableProviders = providers.flatMap((provider) =>
		provider.registrationId
			? [
					{
						value: provider.registrationId,
						label: `${provider.displayName ?? provider.registrationId}${provider.baseUrl ? ` · ${provider.baseUrl}` : ""}`,
						type: provider.providerType,
					},
				]
			: [],
	);
	const primaryProviders = selectableProviders.filter(
		(provider) => provider.type === "GITHUB" || provider.type === "GITLAB",
	);
	const patch = (value: Partial<WorkspaceAccessPolicySettings>) =>
		setSettings({ ...settings, ...value });

	return (
		<form
			className="space-y-6"
			onSubmit={(event) => {
				event.preventDefault();
				if (!pending) onSave({ version: policy.version, enabled, settings });
			}}
		>
			<h2 className="text-xl font-semibold">Access-request policy</h2>
			{!policy.emailConfigured && (
				<p className="text-sm text-destructive">
					Email is not configured on this instance. Requests remain visible here, but email cannot
					be delivered until an instance administrator configures SMTP.
				</p>
			)}
			<Field orientation="horizontal">
				<Checkbox
					id={`${id}-enabled`}
					checked={enabled}
					onCheckedChange={setEnabled}
					disabled={pending}
				/>
				<FieldLabel htmlFor={`${id}-enabled`}>Accept access requests</FieldLabel>
			</Field>
			<p className="text-sm text-muted-foreground">
				Pausing requests does not remove existing access. Saving a changed policy requires pending
				applicants to review it again before approval.
			</p>
			<FieldGroup>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-primary`}>Primary sign-in provider</FieldLabel>
					<Select
						value={settings.primaryRegistrationId || null}
						onValueChange={(value) => patch({ primaryRegistrationId: value ?? "" })}
						items={primaryProviders}
						disabled={pending}
					>
						<SelectTrigger
							id={`${id}-primary`}
							aria-label="Primary sign-in provider"
							className="w-full @md/field-group:w-56"
						>
							<SelectValue placeholder="Choose a provider" />
						</SelectTrigger>
						<SelectContent aria-label="Primary sign-in provider">
							{primaryProviders.map((provider) => (
								<SelectItem key={provider.value} value={provider.value}>
									{provider.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
				<Field>
					<FieldLabel htmlFor={`${id}-intro`}>Welcome and code of conduct (Markdown)</FieldLabel>
					<Textarea
						id={`${id}-intro`}
						value={settings.introductionMarkdown}
						required
						maxLength={20000}
						onChange={(event) => patch({ introductionMarkdown: event.target.value })}
						disabled={pending}
					/>
				</Field>
				<Field>
					<FieldLabel htmlFor={`${id}-ack`}>Acknowledgement label</FieldLabel>
					<Input
						id={`${id}-ack`}
						value={settings.acknowledgementLabel}
						required
						maxLength={300}
						onChange={(event) => patch({ acknowledgementLabel: event.target.value })}
						disabled={pending}
					/>
				</Field>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-maintainers`}>Responsible maintainer team</FieldLabel>
					<Select
						value={settings.maintainerTeamId || null}
						onValueChange={(value) => patch({ maintainerTeamId: value ?? 0 })}
						items={teams.map((team) => ({ value: team.id, label: team.name }))}
						disabled={pending}
					>
						<SelectTrigger
							id={`${id}-maintainers`}
							aria-label="Responsible maintainer team"
							className="w-full @md/field-group:w-56"
						>
							<SelectValue placeholder="Choose a team" />
						</SelectTrigger>
						<SelectContent aria-label="Responsible maintainer team">
							{teams.map((team) => (
								<SelectItem key={team.id} value={team.id}>
									{team.name}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
				<FieldSet>
					<FieldLegend>Teams applicants may request</FieldLegend>
					{teams.length === 0 && (
						<p className="text-muted-foreground">
							Synchronize your organization teams before configuring access requests.
						</p>
					)}
					{teams.map((team) => (
						<Field key={team.id} orientation="horizontal">
							<Checkbox
								id={`${id}-team-${team.id}`}
								checked={settings.requestableTeamIds.includes(team.id)}
								disabled={pending}
								onCheckedChange={(checked) =>
									patch({
										requestableTeamIds: checked
											? [...settings.requestableTeamIds, team.id]
											: settings.requestableTeamIds.filter((value) => value !== team.id),
									})
								}
							/>
							<FieldLabel htmlFor={`${id}-team-${team.id}`}>{team.name}</FieldLabel>
						</Field>
					))}
				</FieldSet>
				<FieldSet>
					<FieldLegend>Additional accounts required</FieldLegend>
					{selectableProviders
						.filter(
							(provider) =>
								provider.type === "OIDC" ||
								provider.type === "SLACK" ||
								provider.type === "OUTLINE",
						)
						.map((provider) => {
							const requirement = settings.requiredLinks.find(
								(link) => link.registrationId === provider.value,
							);
							return (
								<div key={provider.value} className="space-y-2">
									<Field orientation="horizontal">
										<Checkbox
											id={`${id}-provider-${provider.value}`}
											checked={!!requirement}
											disabled={pending}
											onCheckedChange={(checked) =>
												patch({
													requiredLinks: checked
														? [...settings.requiredLinks, { registrationId: provider.value }]
														: settings.requiredLinks.filter(
																(link) => link.registrationId !== provider.value,
															),
												})
											}
										/>
										<FieldLabel htmlFor={`${id}-provider-${provider.value}`}>
											{provider.label}
										</FieldLabel>
									</Field>
									{requirement && (provider.type === "SLACK" || provider.type === "OUTLINE") && (
										<Field orientation="responsive">
											<FieldLabel htmlFor={`${id}-slack-${provider.value}`}>
												Required {provider.type === "SLACK" ? "Slack workspace" : "Outline team"} ID
											</FieldLabel>
											<Input
												id={`${id}-slack-${provider.value}`}
												value={requirement.teamId ?? ""}
												required
												disabled={pending}
												onChange={(event) =>
													patch({
														requiredLinks: settings.requiredLinks.map((link) =>
															link.registrationId === provider.value
																? { ...link, teamId: event.target.value }
																: link,
														),
													})
												}
												className="w-full @md/field-group:w-56"
											/>
										</Field>
									)}
								</div>
							);
						})}
				</FieldSet>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-mail`}>Admin notification mailbox</FieldLabel>
					<Input
						id={`${id}-mail`}
						type="email"
						required
						maxLength={320}
						value={settings.adminMailbox}
						onChange={(event) => patch({ adminMailbox: event.target.value })}
						disabled={pending}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-duration`}>Maximum access duration (days)</FieldLabel>
					<Input
						id={`${id}-duration`}
						type="number"
						min={1}
						max={3650}
						required
						value={settings.maximumDurationDays ?? ""}
						onChange={(event) => patch({ maximumDurationDays: Number(event.target.value) })}
						disabled={pending}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-reminder`}>Remind before expiry (days)</FieldLabel>
					<Input
						id={`${id}-reminder`}
						type="number"
						min={1}
						max={365}
						required
						value={settings.reminderDays ?? ""}
						onChange={(event) => patch({ reminderDays: Number(event.target.value) })}
						disabled={pending}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-retention`}>
						Submission retention after expiry (days, optional)
					</FieldLabel>
					<Input
						id={`${id}-retention`}
						type="number"
						min={1}
						max={3650}
						value={settings.personalDataRetentionDays ?? ""}
						onChange={(event) =>
							patch({
								personalDataRetentionDays: event.target.value
									? Number(event.target.value)
									: undefined,
							})
						}
						disabled={pending}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
			</FieldGroup>
			<p className="text-sm text-muted-foreground">
				Leave retention blank to keep submissions until an erasure request. Retention does not
				delete shared accounts, research consent or audit history, and waits for unresolved requests
				and external revocations.
			</p>
			<section className="space-y-4" aria-labelledby={`${id}-notices-heading`}>
				<h3 id={`${id}-notices-heading`} className="font-semibold">
					Required workspace notices
				</h3>
				<p className="text-sm text-muted-foreground">
					Use these for workspace policies, not optional research participation. Each saved
					submission records the exact notices acknowledged.
				</p>
				{settings.notices.map((notice, index) => (
					<FieldSet key={index} className="rounded-lg border p-4">
						<FieldLegend>Notice {index + 1}</FieldLegend>
						<Field>
							<FieldLabel htmlFor={`${id}-notice-key-${index}`}>Stable key</FieldLabel>
							<Input
								id={`${id}-notice-key-${index}`}
								required
								pattern="[a-z][a-z0-9-]{0,63}"
								value={notice.key}
								disabled={pending}
								onChange={(event) =>
									patch({
										notices: settings.notices.map((value, position) =>
											position === index ? { ...value, key: event.target.value } : value,
										),
									})
								}
							/>
						</Field>
						<Field>
							<FieldLabel htmlFor={`${id}-notice-title-${index}`}>Title</FieldLabel>
							<Input
								id={`${id}-notice-title-${index}`}
								required
								maxLength={200}
								value={notice.title}
								disabled={pending}
								onChange={(event) =>
									patch({
										notices: settings.notices.map((value, position) =>
											position === index ? { ...value, title: event.target.value } : value,
										),
									})
								}
							/>
						</Field>
						<Field>
							<FieldLabel htmlFor={`${id}-notice-text-${index}`}>Notice (Markdown)</FieldLabel>
							<Textarea
								id={`${id}-notice-text-${index}`}
								required
								maxLength={20000}
								value={notice.markdown}
								disabled={pending}
								onChange={(event) =>
									patch({
										notices: settings.notices.map((value, position) =>
											position === index ? { ...value, markdown: event.target.value } : value,
										),
									})
								}
							/>
						</Field>
						<Button
							variant="outline"
							type="button"
							disabled={pending}
							onClick={() =>
								patch({ notices: settings.notices.filter((_, position) => position !== index) })
							}
						>
							Remove notice {index + 1}
						</Button>
					</FieldSet>
				))}
				<Button
					variant="outline"
					type="button"
					disabled={pending || settings.notices.length >= 8}
					onClick={() =>
						patch({ notices: [...settings.notices, { key: "", title: "", markdown: "" }] })
					}
				>
					Add notice
				</Button>
			</section>
			{error && (
				<p role="alert" className="text-destructive">
					{error}
				</p>
			)}
			<Button
				type="submit"
				disabled={pending || !settings.primaryRegistrationId || !settings.maintainerTeamId}
			>
				{pending && <Spinner />}
				{pending ? "Saving…" : "Save access policy"}
			</Button>
		</form>
	);
}
