/**
 * The one clock reading behind every story's timestamps, read once per module load, as in the
 * webapp (`webapp/src/stories/story-clock.ts`). Stories only.
 */
// oxlint-disable-next-line hephaestus/no-nondeterministic-render -- The single reading the stories are built on.
export const STORY_NOW = Date.now();

export function minutesBefore(minutes: number): string {
	return new Date(STORY_NOW - minutes * 60_000).toISOString();
}
