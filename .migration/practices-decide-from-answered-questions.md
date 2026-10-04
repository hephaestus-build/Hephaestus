#### 🔴 Practice reviews answer questions, and rules decide the outcome

This is a coordinated pre-1.0 cutover, not a rolling upgrade. A review no longer states an outcome or a
severity: it answers the practice's questions, and the server decides from the practice's rules. Do not
run an old review runtime or API client against the new server.

1. Stop review scheduling and let reviews finish, or cancel them. Stop every runtime role.
2. Create a database backup and verify that you can restore it into a separate database.
3. Deploy matching server, review runtime, webapp and browser extension versions and let the forward
   migration finish. Every practice, current practice version, instance customization and adopted
   catalogue version that Hephaestus reviews receives starting questions — whether the work gives the
   practice an occasion, whether it meets the standard, and whether a shortfall is major or critical —
   with rules that restate what its criteria already asked. Earlier practice versions and earlier
   observations receive no answers, because none were given. Observations with the informational
   severity become minor.
4. Update custom catalogue files and API clients:
   - A practice with automated review carries `judgment`: `questions` (each with `key`, `title`,
     `question`, `yes`, `no`) and ordered `rules` (each with `id`, `when`, `outcome`, `severity` exactly
     for `NOT_MET`, and `reason`). The last rule has no conditions, and every rule must be reachable.
     A practice without automated review carries none.
   - Criteria are guidance: remove instructions that name results or severities; the rules decide them.
   - `severity` no longer accepts `INFO`.
5. Before restarting reviews, open the practices you rely on in the practice editor and replace the
   starting questions with questions about each practice's own facts, beginning with instance
   customizations, then workspace copies through catalogue updates, then custom practices. Then verify
   practice editing, one review, its recorded answers and result, and delivery.

**Recovery:** Stop all roles and restore the verified pre-upgrade database backup together with the
previous application images. The starting questions given to existing practices and the mapped
severities cannot be told apart from later edits; there is no automatic reverse conversion.
