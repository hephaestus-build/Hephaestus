import { useId, useState } from "react";

import type { SubmitWorkspaceAccessRequest, WorkspaceAccessForm } from "@/api/types.gen";
import { UntrustedMarkdown, UNTRUSTED_MARKDOWN_PROSE } from "@/components/common/UntrustedMarkdown";
import { useNow } from "@/components/common/use-now";
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

interface WorkspaceAccessRequestFormProps {
	form: WorkspaceAccessForm;
	pending: boolean;
	error?: string;
	onSubmit: (submission: SubmitWorkspaceAccessRequest) => void;
}

/** Owns an unsaved application; the route remounts it when the policy version changes. */
export function WorkspaceAccessRequestForm({
	form,
	pending,
	error,
	onSubmit,
}: WorkspaceAccessRequestFormProps) {
	const id = useId();
	const now = useNow();
	const [maintainer, setMaintainer] = useState<number | null>(null);
	const [teams, setTeams] = useState<number[]>([]);
	const [expires, setExpires] = useState("");
	const [comments, setComments] = useState("");
	const [introductionAcknowledged, setIntroductionAcknowledged] = useState(false);
	const [notices, setNotices] = useState<string[]>([]);
	const maximum = new Date(now + (form.maximumDurationDays ?? 0) * 86_400_000)
		.toISOString()
		.slice(0, 10);
	const minimum = new Date(now + 86_400_000).toISOString().slice(0, 10);
	const ready =
		maintainer !== null &&
		expires >= minimum &&
		expires <= maximum &&
		introductionAcknowledged &&
		form.notices.every((notice) => notices.includes(notice.key));

	return (
		<form
			className="space-y-6"
			onSubmit={(event) => {
				event.preventDefault();
				if (!ready || pending) return;
				onSubmit({
					policyVersion: form.policyVersion,
					introductionAcknowledged,
					acknowledgedNoticeKeys: notices,
					comments: comments.trim() || undefined,
					details: {
						maintainerAccountId: maintainer,
						teamIds: teams,
						expiresAt: new Date(`${expires}T00:00:00Z`),
					},
				});
			}}
		>
			<h2 className="text-xl font-semibold">Request access</h2>
			<div className={UNTRUSTED_MARKDOWN_PROSE}>
				<UntrustedMarkdown>{form.introductionMarkdown}</UntrustedMarkdown>
			</div>
			<Field orientation="horizontal">
				<Checkbox
					id={`${id}-introduction`}
					checked={introductionAcknowledged}
					onCheckedChange={(checked) => setIntroductionAcknowledged(checked)}
					disabled={pending}
				/>
				<FieldLabel htmlFor={`${id}-introduction`}>{form.acknowledgementLabel}</FieldLabel>
			</Field>
			<FieldGroup>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-maintainer`}>Responsible maintainer</FieldLabel>
					<Select
						value={maintainer}
						onValueChange={setMaintainer}
						items={form.maintainers.map((item) => ({
							value: item.accountId,
							label: item.displayName,
						}))}
						disabled={pending}
					>
						<SelectTrigger
							id={`${id}-maintainer`}
							className="w-full @md/field-group:w-56"
							aria-label="Responsible maintainer"
						>
							<SelectValue placeholder="Choose a maintainer" />
						</SelectTrigger>
						<SelectContent aria-label="Responsible maintainer">
							{form.maintainers.map((item) => (
								<SelectItem key={item.accountId} value={item.accountId}>
									{item.displayName}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
				<FieldSet>
					<FieldLegend>Teams you need</FieldLegend>
					{form.requestableTeams.length === 0 && (
						<p className="text-sm text-muted-foreground">No additional teams are offered.</p>
					)}
					{form.requestableTeams.map((team) => (
						<Field key={team.id} orientation="horizontal">
							<Checkbox
								id={`${id}-team-${team.id}`}
								checked={teams.includes(team.id)}
								disabled={pending}
								onCheckedChange={(checked) =>
									setTeams(
										checked ? [...teams, team.id] : teams.filter((value) => value !== team.id),
									)
								}
							/>
							<FieldLabel htmlFor={`${id}-team-${team.id}`}>{team.name}</FieldLabel>
						</Field>
					))}
				</FieldSet>
				<Field orientation="responsive">
					<FieldLabel htmlFor={`${id}-expires`}>Access until (00:00 UTC)</FieldLabel>
					<Input
						id={`${id}-expires`}
						type="date"
						required
						min={minimum}
						max={maximum}
						value={expires}
						onChange={(event) => setExpires(event.target.value)}
						disabled={pending}
						className="w-full @md/field-group:w-56"
					/>
				</Field>
				<Field>
					<FieldLabel htmlFor={`${id}-comments`}>Additional comments (optional)</FieldLabel>
					<Textarea
						id={`${id}-comments`}
						value={comments}
						maxLength={4000}
						onChange={(event) => setComments(event.target.value)}
						disabled={pending}
					/>
				</Field>
			</FieldGroup>
			{form.notices.map((notice) => (
				<section
					key={notice.key}
					className="space-y-3"
					aria-labelledby={`${id}-notice-title-${notice.key}`}
				>
					<h3 id={`${id}-notice-title-${notice.key}`} className="font-semibold">
						{notice.title}
					</h3>
					<div className={UNTRUSTED_MARKDOWN_PROSE}>
						<UntrustedMarkdown>{notice.markdown}</UntrustedMarkdown>
					</div>
					<Field orientation="horizontal">
						<Checkbox
							id={`${id}-notice-${notice.key}`}
							checked={notices.includes(notice.key)}
							disabled={pending}
							onCheckedChange={(checked) =>
								setNotices(
									checked ? [...notices, notice.key] : notices.filter((key) => key !== notice.key),
								)
							}
						/>
						<FieldLabel htmlFor={`${id}-notice-${notice.key}`}>
							I acknowledge {notice.title}
						</FieldLabel>
					</Field>
				</section>
			))}
			<p className="text-sm text-muted-foreground">
				These workspace acknowledgements do not change your optional research consent.
			</p>
			{error && (
				<p role="alert" className="text-sm text-destructive">
					{error}
				</p>
			)}
			<Button type="submit" disabled={!ready || pending}>
				{pending && <Spinner />}
				{pending ? "Submitting…" : "Request access"}
			</Button>
		</form>
	);
}
