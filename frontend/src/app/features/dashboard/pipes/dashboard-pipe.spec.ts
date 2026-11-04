import { describe, expect, it } from 'vitest';
import { DashboardPipeCard, pipeIdentity, pipeState } from './dashboard-pipe';

describe('dashboard Pipe execution identity', () => {
  const card = { id: 1, execution: { status: 'FAILED', outcome: 'ERROR', executionId: 'first', finishedAt: '2026-11-01' }, steps: [{ result: { key: 'check', type: 'ALERT', resourceId: 2, status: 'ERROR', errorCode: 'FIRST' }, alertExecution: null }] } as unknown as DashboardPipeCard;
  it('does not treat a repeated result with a different execution or time as a different state', () => {
    expect(pipeIdentity({ ...card, execution: { ...card.execution!, executionId: 'second', finishedAt: '2026-11-03' } })).toBe(pipeIdentity(card));
  });
  it('releases an ignored state when a step changes its error', () => {
    const changed = { ...card, steps: [{ ...card.steps[0], result: { ...card.steps[0].result, errorCode: 'SECOND' } }] };
    expect(pipeIdentity(changed)).not.toBe(pipeIdentity(card));
  });
  it('uses the recorded outcome and keeps an unexecuted Pipe neutral', () => {
    expect(pipeState(card)).toBe('error');
    expect(pipeState({ ...card, execution: null })).toBe('none');
  });
  it('does not ignore a different alert exception', () => {
    const first = { ...card, steps: [{ ...card.steps[0], alertExecution: { status: 'ERROR', errorType: 'Timeout', errorMessage: 'timeout' } as never }] };
    const second = { ...card, steps: [{ ...card.steps[0], alertExecution: { status: 'ERROR', errorType: 'Authentication', errorMessage: 'authentication' } as never }] };
    expect(pipeIdentity(first)).not.toBe(pipeIdentity(second));
  });
  it('uses the semantic formatter identity instead of timestamps in a raw result', () => {
    const first = { ...card, steps: [{ ...card.steps[0], alertExecution: { statusMessage: { checkedAt: 'first', latencyMs: 10 } } as never }] };
    const second = { ...card, steps: [{ ...card.steps[0], alertExecution: { statusMessage: { checkedAt: 'second', latencyMs: 20 } } as never }] };
    expect(pipeIdentity(first, () => 'same-http-failure')).toBe(pipeIdentity(second, () => 'same-http-failure'));
  });
});
