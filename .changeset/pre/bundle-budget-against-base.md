---
---

The web app build holds each change's bundle growth to the build of the commit that the change merges onto, instead of to a committed baseline. Growth that a change accepts is recorded together with the pull request that accepted it. Recording refuses a checkout whose installed packages do not match the lockfile, because such an install builds a different bundle than CI. Nothing that a user, an admin or an operator sees changes.
