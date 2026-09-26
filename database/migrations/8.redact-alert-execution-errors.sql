UPDATE audit.logs AS log
SET data = log.data - 'errorMessage'
FROM audit.log_events AS event
WHERE log.event_id = event.id
  AND event.code = 'ALERT_EXECUTION_COMPLETED'
  AND log.data ? 'errorMessage';

UPDATE core.alert_executions
SET error_message = NULL,
    error_stack_trace = NULL
WHERE error_message IS NOT NULL
   OR error_stack_trace IS NOT NULL;
