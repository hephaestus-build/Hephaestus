// sandbox-paths.ts — the layout that a sandbox's image gives its run kinds. Its own module, so a kind imports it without
// importing the driver, which imports every kind.

/**
 * Where a kind works: Pi's working directory and its agent directory. The image gives both to UID 1000, and they last
 * as long as the sandbox, so a conversation keeps its files from one turn to the next. The container's
 * ephemeral-storage limit bounds them.
 */
export const WORKSPACE = "/workspace/work";
export const AGENT_DIR = "/workspace/.pi";
