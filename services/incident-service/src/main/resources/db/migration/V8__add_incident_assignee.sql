-- Adds operator assignment, orthogonal to lifecycle status. Purely additive (nullable column,
-- no default required, no existing row rewritten in an incompatible way) — backward-compatible;
-- a previous-version application ignores this column entirely. See
-- docs/development/operations.md's migration backward-compatibility table.
ALTER TABLE incidents.incidents ADD COLUMN assignee_id VARCHAR(100);

COMMENT ON COLUMN incidents.incidents.assignee_id IS
    'Stable actor subject ID of the operator currently assigned, or NULL if unassigned.';

CREATE INDEX idx_incidents_assignee_id ON incidents.incidents (assignee_id);
