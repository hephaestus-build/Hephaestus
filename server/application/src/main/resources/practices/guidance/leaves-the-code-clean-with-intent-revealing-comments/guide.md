## How to do it

- Before you ask for a review, delete the code that you commented out while you worked. Version control keeps the old version for you.
- Remove what you used only while you worked. Examples are a breakpoint, a “TEMP” stub, or an empty comment where code was meant to go.
- If your project allows a TODO, link it to a tracked issue.
- Delete a comment that only repeats its line. You must keep every comment up to date when the code changes, so each one must earn its place.
- Where a line looks surprising, add a comment that gives the reason. Name the intent, a constraint, a unit, a caveat or a reference. For example, instead of `// sleep for 250 ms` above `sleep(250)`, write `// stay under the rate limit`.

## When it does not apply

- The change is empty.
- The change holds only generated or vendored files, such as lockfiles, build output, specs or migrations.
- The change adds no code. It changes only documentation, diagrams, images or configuration.

It still applies when the change adds markup or templates that make the app work.

## Common mistakes

- Deleting comments that help. A comment on a function can tell its callers what it takes, what it returns, and how it fails. A code example in that comment shows how to call it. Keep both when they say more than the declaration does.
- Keeping old code in a comment in case you need it again. Nobody updates it, so it soon stops matching the code around it. Instead, delete it and find it in version control when you need it.
- Writing about your edits instead of the code, such as “FIX 2: added the date field” or “moved from Foo”. The next reader sees only the code as it is now. Instead, delete the note, or say why the code is this way.

## Sources

- [What to look for in a code review](https://google.github.io/eng-practices/review/reviewer/looking-for.html), Google Engineering Practices. Google recommends comments for reasons that the code cannot show, and function documentation for callers.
- [Google Python Style Guide](https://google.github.io/styleguide/pyguide.html), Google. Google recommends comments for tricky code, and TODO links that give context, preferably through a tracked bug.
- [The secret life of commented-out source code](https://doi.org/10.1145/3387904.3389259), Pham and Yang, 2020. Commented-out code is often not maintained, and the authors point to version control for keeping old code.
- [A large-scale empirical study on code-comment inconsistencies](https://doi.org/10.1109/ICPC.2019.00019), Wen, Nagy, Bavota and Lanza, 2019. Keeping comments up to date as the code changes takes a lot of time and care.
