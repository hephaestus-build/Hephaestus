@AGENTS.md

## Claude Code

`webapp/CLAUDE.md` and `server/CLAUDE.md` import their own trees' guides the same way.
Do **not** import them from here.
A nested `CLAUDE.md` loads only after Claude reads a file in that tree.
Thus, sessions that never enter a tree do not load its package guide.
`docs/contributor/ai-agent-workflow.mdx` has the rest.
