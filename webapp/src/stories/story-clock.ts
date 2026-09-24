/**
 * The one clock reading behind every story's relative timestamps.
 *
 * A hard-coded instant is not an option: it drifts into "8 months ago" as the calendar moves and
 * puts every "expires in …" branch permanently in the past. Reading once per module load rather
 * than once per story keeps two timestamps in the same story consistent with each other and keeps a
 * rendered phrase identical across the stories in one run.
 *
 * Stories only. Component code takes the time from `useNow`.
 */
// oxlint-disable-next-line no-restricted-properties, hephaestus/no-nondeterministic-render -- The single reading the story tree is built on, held here so that no story reads a moving clock itself.
export const STORY_NOW = Date.now();

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

export const minutesBefore = (minutes: number): Date => new Date(STORY_NOW - minutes * MINUTE_MS);

export const minutesAfter = (minutes: number): Date => new Date(STORY_NOW + minutes * MINUTE_MS);

export const hoursBefore = (hours: number): Date => new Date(STORY_NOW - hours * HOUR_MS);

export const daysBefore = (days: number): Date => new Date(STORY_NOW - days * DAY_MS);

export const daysAfter = (days: number): Date => new Date(STORY_NOW + days * DAY_MS);
