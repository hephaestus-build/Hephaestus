---
"hephaestus": patch
---

Product feedback no longer accepts a private repository as its public destination, and a public repository that later turns private stops feeding its issue titles to the model. A report now moves on after 10 minutes without a worker: it becomes a private issue or stays saved for the admins, instead of waiting forever. On a private issue, the **Possibly related** link now points to the public repository where that issue lives. A report that is not on GitHub no longer claims that the admins have it or got an email, because an instance without email delivery sends none. It now says the report is saved for the admins, who read it in the admin console.
