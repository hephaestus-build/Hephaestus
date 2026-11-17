---
"hephaestus": patch
---

Feedback no longer claims that something is absent from a whole repository when the review searched only part of it. A result that says a whole category is missing, as in "no persistence vocabulary", "nothing auth-related" or "no cache of any kind", is now withheld, because a search for a few words cannot show that. A result that lists several missing things, as in "no entity, controller, or cache classes", needs a search for each of them. Before, such a sentence could reach a developer while the repository did contain an entity, a repository class or an endpoint.
