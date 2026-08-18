INSERT INTO audit.log_events (code)
VALUES ('USER_LOGOUT')
ON CONFLICT (code) DO NOTHING;
