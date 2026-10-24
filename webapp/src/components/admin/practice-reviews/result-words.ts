/** A missing summary represents denied source authority, while the result’s facts remain. */
export function resultSummary(result: { summary?: string | null }): string {
	return result.summary ?? "Observation text unavailable";
}

export const RESULT_WORDS_UNAVAILABLE =
	"The observation text and evidence are unavailable because source access could not be verified.";
