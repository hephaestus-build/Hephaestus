/** The directory the server reads when a request names none. */
export const DEFAULT_PRACTICE_DIRECTORY = ".hephaestus/practices";

/**
 * The server's rule for a directory (`SetPracticeRepositoryRequestDTO`): a path relative to the
 * repository root, of `[A-Za-z0-9._-]` segments, with no leading or trailing slash and no `.`, `..`
 * or `.git` segment. The form checks it before sending, so the field can say what to change.
 */
const DIRECTORY_PATTERN =
	/^(?!\/)(?!.*(?:^|\/)(?:\.\.?|\.git)(?:\/|$))[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/u;
const DIRECTORY_MAX_LENGTH = 255;

/** Whether the server accepts `directory`, already trimmed, as the directory of the folders. */
export function isPracticeDirectory(directory: string): boolean {
	return directory.length <= DIRECTORY_MAX_LENGTH && DIRECTORY_PATTERN.test(directory);
}
