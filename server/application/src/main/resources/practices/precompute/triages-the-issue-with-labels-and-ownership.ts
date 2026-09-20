interface IssueMeta {
	issue_type?: string | null;
	labels?: string[];
	assignees?: string[];
	milestone?: string | null;
	state?: string;
}

export default function triagesTheIssueWithMetadata(
	_repo: string,
	_diff: Map<string, unknown>,
	m: IssueMeta,
) {
	const labels = m.labels ?? [];
	const issueType = m.issue_type ?? "";
	const hasIssueType = issueType !== "";
	const assignees = m.assignees ?? [];
	const milestone = m.milestone ?? "";
	const hasMilestone = milestone !== "";
	const state = (m.state ?? "").toUpperCase();
	const directions: string[] = [
		`Classification metadata: issueType=${hasIssueType ? `"${issueType}"` : "none"}, labels=${labels.length} [${labels.slice(0, 8).join(", ")}], assignees=${assignees.length}, milestone=${hasMilestone ? `"${milestone}"` : "none"}, state=${state || "?"}.`,
	];
	return {
		hints: [],
		metrics: {
			hasIssueType: hasIssueType ? 1 : 0,
			labelCount: labels.length,
			assigneeCount: assignees.length,
			hasMilestone: hasMilestone ? 1 : 0,
		},
		directions,
	};
}
