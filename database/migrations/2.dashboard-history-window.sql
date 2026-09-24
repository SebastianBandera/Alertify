-- Look-back window summarized on every dashboard tile, in days.
INSERT INTO core.system_configurations (
    name,
    value,
    value_hidden
) VALUES (
    'DASHBOARD_HISTORY_WINDOW',
    '{"days": 10}'::jsonb,
    false
)
ON CONFLICT (name) DO NOTHING;
