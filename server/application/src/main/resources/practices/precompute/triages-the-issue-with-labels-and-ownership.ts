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
	const issueType = m.issue_type ?? null;
	const assignees = m.assignees ?? [];
	const milestone = m.milestone ?? null;
	const state = (m.state ?? "").toUpperCase();
	const directions: string[] = [
		`Classification metadata: issueType=${issueType ? `"${issueType}"` : "none"}, labels=${labels.length} [${labels.slice(0, 8).join(", ")}], assignees=${assignees.length}, milestone=${milestone ? `"${milestone}"` : "none"}, state=${state || "?"}.`,
	];
	return {
		hints: [],
		metrics: {
			hasIssueType: issueType ? 1 : 0,
			labelCount: labels.length,
			assigneeCount: assignees.length,
			hasMilestone: milestone ? 1 : 0,
		},
		directions,
	};
}
