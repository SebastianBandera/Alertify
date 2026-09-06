-- Seeds a starter tag catalog for alerts and procedures. Migrations are applied
-- exactly once, so any tag the user later renames or deletes is never restored.
INSERT INTO core.tags (scope, name, color)
SELECT scopes.scope, seed.name, seed.color
FROM (VALUES
        ('Production',     '#D64545'),
        ('Pre-Production', '#E39A2B'),
        ('Staging',        '#9B6DD6'),
        ('QA',             '#3BA55D'),
        ('Dev',            '#4C8DF6'),
        ('Local',          '#7A8899'),
        ('Critical',       '#B02A37'),
        ('High',           '#E2681A'),
        ('Medium',         '#C9A227'),
        ('Low',            '#6C8EA4')
     ) AS seed (name, color)
CROSS JOIN (VALUES ('ALERT'), ('PROCEDURE')) AS scopes (scope)
ON CONFLICT DO NOTHING;
