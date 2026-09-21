export interface ReviewPractice {
	slug: string;
	area?: string;
	readsSources?: readonly string[];
}

export type EvidenceLane =
	| "pull-request"
	| "linked-work"
	| "review"
	| "code"
	| "issue"
	| "document"
	| "conversation"
	| "unknown";

export interface ReviewGroup {
	id: string;
	lane: EvidenceLane;
	practiceSlugs: string[];
}

export interface ReviewTree {
	practiceCount: number;
	groups: ReviewGroup[];
}

export function missingPracticeSlugs(
	selected: readonly string[],
	observed: readonly string[],
): string[] {
	const covered = new Set(observed);
	return selected.filter((slug) => !covered.has(slug));
}

const LANE_ORDER: readonly EvidenceLane[] = [
	"pull-request",
	"linked-work",
	"review",
	"issue",
	"document",
	"conversation",
	"code",
	"unknown",
];

export function resolveReviewConcurrency(value: string | undefined, practiceCount: number): number {
	if (!Number.isInteger(practiceCount) || practiceCount < 0) {
		throw new Error(`practiceCount must be a non-negative integer, got: ${practiceCount}`);
	}
	const concurrency =
		value === undefined ? Math.max(1, Math.min(8, Math.ceil(practiceCount / 4))) : Number(value);
	if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 8) {
		throw new Error(`PI_REVIEW_CONCURRENCY must be an integer from 1 to 8, got: ${concurrency}`);
	}
	return concurrency;
}

function evidenceLane(sources: readonly string[]): EvidenceLane {
	if (sources.some((source) => source.startsWith("chat."))) {
		return "conversation";
	}
	if (sources.some((source) => source.startsWith("docs.") || source === "outline.documents")) {
		return "document";
	}
	if (sources.some((source) => source.startsWith("scm.issue."))) {
		return "issue";
	}
	if (sources.includes("scm.review-threads") || sources.includes("scm.pull-request.comments")) {
		return "review";
	}
	if (sources.includes("scm.linked-work-items")) {
		return "linked-work";
	}
	if (sources.includes("scm.pull-request.diff") || sources.includes("scm.repository.tree")) {
		return "code";
	}
	if (sources.some((source) => source.startsWith("scm.pull-request."))) {
		return "pull-request";
	}
	return "unknown";
}

function normalizedSources(sources: readonly string[] | undefined): string[] {
	return [...new Set((sources ?? []).map((source) => source.trim()).filter(Boolean))].toSorted();
}

export function buildReviewTree(
	practices: readonly ReviewPractice[],
	maxPracticesPerGroup: number,
): ReviewTree {
	if (!Number.isInteger(maxPracticesPerGroup) || maxPracticesPerGroup <= 0) {
		throw new Error(
			`maxPracticesPerGroup must be a positive integer, got: ${maxPracticesPerGroup}`,
		);
	}

	const seen = new Set<string>();
	const byGroup = new Map<
		string,
		{ lane: EvidenceLane; area: string | null; entries: { slug: string; sources: string[] }[] }
	>();
	for (const practice of practices) {
		const slug = practice.slug.trim();
		if (!slug) {
			throw new Error("every practice needs a non-empty slug");
		}
		if (seen.has(slug)) {
			throw new Error(`duplicate practice slug: ${slug}`);
		}
		seen.add(slug);

		const sources = normalizedSources(practice.readsSources);
		const lane = evidenceLane(sources);
		const configuredArea = practice.area?.trim();
		const area = configuredArea === undefined || configuredArea === "" ? null : configuredArea;
		const key = `${lane}\0${area ?? ""}`;
		const group = byGroup.get(key) ?? { lane, area, entries: [] };
		group.entries.push({ slug, sources });
		byGroup.set(key, group);
	}

	const groups: ReviewGroup[] = [];
	for (const lane of LANE_ORDER) {
		let laneGroupIndex = 0;
		const laneGroups = [...byGroup.values()]
			.filter((group) => group.lane === lane)
			.toSorted((left, right) => (left.area ?? "").localeCompare(right.area ?? ""));
		for (const group of laneGroups) {
			const entries = group.entries.toSorted((left, right) => left.slug.localeCompare(right.slug));
			for (let offset = 0; offset < entries.length; offset += maxPracticesPerGroup) {
				const chunk = entries.slice(offset, offset + maxPracticesPerGroup);
				laneGroupIndex += 1;
				groups.push({
					id: `${lane}-${laneGroupIndex}`,
					lane,
					practiceSlugs: chunk.map((entry) => entry.slug),
				});
			}
		}
	}

	return { practiceCount: seen.size, groups };
}

export async function mapConcurrent<T, R>(
	items: readonly T[],
	concurrency: number,
	work: (item: T, index: number) => R | Promise<R>,
	signal?: AbortSignal,
): Promise<R[]> {
	if (!Number.isInteger(concurrency) || concurrency <= 0) {
		throw new Error(`concurrency must be a positive integer, got: ${concurrency}`);
	}
	const results: R[] = [];
	results.length = items.length;
	let nextIndex = 0;
	const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
		for (;;) {
			if (signal?.aborted === true) {
				return;
			}
			const index = nextIndex;
			nextIndex += 1;
			if (index >= items.length) {
				return;
			}
			const item = items[index];
			if (item !== undefined) {
				results[index] = await work(item, index);
			}
		}
	});
	await Promise.all(workers);
	return results;
}
