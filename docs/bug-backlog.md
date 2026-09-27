# Bug backlog

Open bugs found while building the dependency automation, kept here until each
is fixed. Fixing one means deleting its row. Every entry was checked against
the code at the location given.

| Id | Bug | Where | Impact | Fix |
| --- | --- | --- | --- | --- |
| B14 | elfydd's `README.md` says `STACK_VERSION` in `.env` drives deploys; deploys read `.stack-version`. `README.md` is consumer-owned, so `./dev update` never refreshes it (the scaffold copy and the synced `AGENTS.md` are correct) | elfydd `README.md:23` | Operators change the wrong setting | Rewrite the paragraph from the scaffold's `README.md` § Update the platform when elfydd migrates to `.stack-version` |
