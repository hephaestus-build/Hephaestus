export interface IssueMetadata {
	title?: string;
	body?: string;
	issue_type?: string | null;
	labels?: string[];
}

export function classifyIssue(metadata: IssueMetadata) {
	const body = (metadata.body ?? "").trim();
	const title = (metadata.title ?? "").trim();
	const issueType = (metadata.issue_type ?? "").toLowerCase();
	const labels = (metadata.labels ?? []).map((label) => label.toLowerCase());

	const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
	const titleNorm = norm(title);
	const bodyNorm = norm(body);
	const titleEcho =
		titleNorm.length > 0 &&
		bodyNorm.length > 0 &&
		(bodyNorm === titleNorm || titleNorm.includes(bodyNorm) || bodyNorm.includes(titleNorm));
	const emptyOrTitleEcho = body.length < 25 || titleEcho;
	const deliverableType =
		/\b(user ?story|story|bug|defect|feature|enhancement|task|chore|requirement|artifact|epic|spike)\b/;
	const hasDeliverableType =
		deliverableType.test(issueType) || labels.some((l) => deliverableType.test(l));
	const looksUmbrella =
		labels.some((l) => /\b(epic|umbrella|meta|initiative|requirement)\b/.test(l)) ||
		/\b(epic|umbrella|initiative)\b/i.test(title);

	return { body, title, issueType, labels, emptyOrTitleEcho, hasDeliverableType, looksUmbrella };
}
