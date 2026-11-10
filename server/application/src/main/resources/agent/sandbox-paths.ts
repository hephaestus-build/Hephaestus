// sandbox-paths.ts — the layout that a sandbox's image gives its run kinds. Its own module, so a kind imports it without
// importing the driver, which imports every kind.

/**
 * Where a kind works: Pi's working directory and its agent directory. Both are on the sandbox's /workspace volume, which
 * lasts as long as the sandbox, so a conversation keeps its files from one turn to the next. The kind creates them; the
 * volume's size limit bounds them.
 */
export const WORKSPACE = "/workspace/work";
export const AGENT_DIR = "/workspace/.pi";
