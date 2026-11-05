import { AlertExecution } from '../../../core/api/alert-api.service';
import { PipeExecution, PipeOutcome, PipeStepResult, PipeTag } from '../../../core/api/pipe-api.service';

export interface DashboardPipeStep {
  readonly result: PipeStepResult;
  readonly templateKey: string | null;
  readonly alertExecution: AlertExecution | null;
}

export interface DashboardPipeCard {
  readonly id: number;
  readonly version: number;
  readonly name: string;
  readonly description: string | null;
  readonly enabled: boolean;
  readonly allowConcurrentExecutions: boolean;
  readonly tags: readonly PipeTag[];
  readonly execution: PipeExecution | null;
  readonly lastOutcome: PipeOutcome | null;
  readonly stateChangedAt: string | null;
  readonly steps: readonly DashboardPipeStep[];
  readonly observedAt: string;
}

export function pipeState(card: DashboardPipeCard): 'error' | 'warn' | 'success' | 'none' {
  const outcome = card.lastOutcome ?? card.execution?.outcome;
  return outcome === 'ERROR' ? 'error' : outcome === 'WARN' ? 'warn'
    : outcome === 'SUCCESS' ? 'success' : 'none';
}

/** Semantic identity deliberately excludes time, durations, execution IDs and procedure outputs. */
export function pipeIdentity(card: DashboardPipeCard, identityOf: (step: DashboardPipeStep) => string = step => JSON.stringify([step.alertExecution?.status, step.alertExecution?.errorType, step.alertExecution?.errorMessage])): string {
  const text = JSON.stringify([card.execution?.outcome, card.execution?.status, card.execution?.errorCode,
    card.steps.map(s => [s.result.key, s.result.type, s.result.resourceId, s.result.status, s.result.outcome, s.result.errorCode, identityOf(s)])]);
  let hash = 2166136261;
  for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
  return (hash >>> 0).toString(16);
}
