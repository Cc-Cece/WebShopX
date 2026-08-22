# ADR-0002: Defer stage reviews until the M0-M9 implementation is complete

- Status: accepted
- Date: 2026-08-23
- Decision owner: project owner

## Context

The migration plan originally required an independent review after every milestone. On
2026-08-23 the project owner explicitly instructed the programmer Agent to continue through
M1-M9 without questions or sub-Agents and to leave review to a manually started Agent after the
complete M0-M9 implementation.

## Decision

- Keep milestone boundaries, commits, tests, reports and rollback points.
- Do not pause for M0-M9 stage review and do not create review conclusions on behalf of a reviewer.
- Prepare one consolidated review packet after M9.
- This exception changes review timing only. It does not authorize merging `main`, tagging, or
  publishing a formal release.

## Consequences

The implementation can proceed continuously. The final reviewer must audit every milestone and
may still reject or request changes in any earlier stage. Until that review, implementation status
means “implemented with local evidence”, not “independently accepted”.
