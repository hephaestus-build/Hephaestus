/**
 * Writes the end-of-test summary k6 documents for `handleSummary` into the run directory the runner
 * mounts at `/results`. Each key of the returned object is a file path; returning one at all replaces
 * the summary k6 would otherwise print, which the generated baseline document reports in full.
 * @param {unknown} data - the summary k6 hands over; `scripts/load-test.ts` reads it back as JSON
 * @returns {Record<string, string>} the summary file and its contents
 */
export function handleSummary(data) {
	return { "/results/summary.json": JSON.stringify(data, null, 2) };
}
