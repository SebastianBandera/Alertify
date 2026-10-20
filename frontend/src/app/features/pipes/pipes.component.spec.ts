import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { EMPTY } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Pipe, PipeApiService, PipeOptions } from '../../core/api/pipe-api.service';
import { LocalizationService } from '../../core/i18n/localization.service';
import { AdminEventChannelService } from '../../core/realtime/admin-event-channel.service';
import { EditorDraftService } from '../../shared/editor-drafts/editor-draft.service';
import { EditorViewportService } from '../../shared/editor-viewport/editor-viewport.service';
import { PipesComponent } from './pipes.component';

type Harness = {
  form: PipesComponent['form'];
  options: PipesComponent['options'];
  openEdit: PipesComponent['openEdit'];
  openBindingEditor: PipesComponent['openBindingEditor'];
  patchBindingEditor: PipesComponent['patchBindingEditor'];
  applyBinding: PipesComponent['applyBinding'];
  cancelBindingEditor: PipesComponent['cancelBindingEditor'];
  removeBinding: PipesComponent['removeBinding'];
  save: PipesComponent['save'];
  moveStep: PipesComponent['moveStep'];
  patchStep: PipesComponent['patchStep'];
  minimizeDraft: PipesComponent['minimizeDraft'];
  restoreDraft: PipesComponent['restoreDraft'];
  templateKeyFor: PipesComponent['templateKeyFor'];
  changeBindingKind: PipesComponent['changeBindingKind'];
};

const OPTIONS: PipeOptions = {
  expressionUtilityFunctions: ['JSON_STRING'],
  resources: [
    { id: 1, name: 'Producer', type: 'PROCEDURE', enabled: true, templateKey: 'example.TokenProcedureTemplate', outputs: ['backup'], artifactInputs: [], stringInputs: [] },
    { id: 2, name: 'Consumer', type: 'PROCEDURE', enabled: true, templateKey: 'example.ConsumerProcedureTemplate', outputs: [], artifactInputs: ['file'], stringInputs: ['jwt', 'other'] },
    { id: 2, name: 'HTTP', type: 'ALERT', enabled: true, templateKey: 'example.WebRequestAlertTemplate', outputs: [], artifactInputs: [], stringInputs: ['headersOverrideJson'] },
  ],
};

const PIPE: Pipe = {
  id: 99, version: 1, name: 'Pipe UI fixture', description: '', enabled: true, allowConcurrentExecutions: false,
  finallyTimeout: 'PT1M', tags: [], createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z',
  steps: [
    { id: 1, key: 'producer', position: 0, type: 'PROCEDURE', phase: 'MAIN', resourceId: 1, resourceName: 'Producer', resourceTemplateKey: 'example.TokenProcedureTemplate', resourceEnabled: true, timeout: 'PT1M', continueOn: ['SUCCESS'], bindings: [] },
    { id: 2, key: 'consumer', position: 1, type: 'PROCEDURE', phase: 'MAIN', resourceId: 2, resourceName: 'Consumer', resourceTemplateKey: 'example.ConsumerProcedureTemplate', resourceEnabled: true, timeout: 'PT1M', continueOn: ['SUCCESS'], bindings: [
      { targetParameterKey: 'jwt', sourceStepKey: 'producer', sourceOutputKey: null, sourceResultPointer: '/accessToken', valueExpression: 'Bearer {{pipe.VALUE}}' },
    ] },
  ],
};

describe('Pipes compact binding editor', () => {
  let fixture: ComponentFixture<PipesComponent>;
  let harness: Harness;
  let storedDraft: { form: ReturnType<Harness['form']>; editing: Pipe | null } | null;
  const page = { content: [], page: { number: 0, totalPages: 0, totalElements: 0 } };
  const api = { options: vi.fn(), list: vi.fn(), listTags: vi.fn(), history: vi.fn(), update: vi.fn(), create: vi.fn() };
  const viewport = { capture: vi.fn(), restore: vi.fn() };
  const drafts = {
    requestedRestore: signal(null), has: () => false, remove: vi.fn(),
    save: vi.fn((kind: string, form: ReturnType<Harness['form']>, editing: Pipe | null) => { storedDraft = structuredClone({ form, editing }); }),
    read: vi.fn(() => storedDraft),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    storedDraft = null;
    api.options.mockResolvedValue(OPTIONS);
    api.list.mockResolvedValue(page);
    api.listTags.mockResolvedValue([]);
    api.history.mockResolvedValue(page);
    api.update.mockResolvedValue(PIPE);
    Element.prototype.scrollIntoView = vi.fn();
    await TestBed.configureTestingModule({
      imports: [PipesComponent],
      providers: [
        { provide: PipeApiService, useValue: api },
        { provide: LocalizationService, useValue: { translateDynamic: (key: string) => key } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
        { provide: AdminEventChannelService, useValue: { on: () => EMPTY } },
        { provide: EditorDraftService, useValue: drafts },
      ],
    }).overrideComponent(PipesComponent, { set: { providers: [{ provide: EditorViewportService, useValue: viewport }] } }).compileComponents();
    fixture = TestBed.createComponent(PipesComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    harness = fixture.componentInstance as unknown as Harness;
    harness.options.set(OPTIONS);
    harness.openEdit(structuredClone(PIPE));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  function editExisting(): void {
    harness.openBindingEditor(1, harness.form().steps[1].bindings[0]);
    fixture.detectChanges();
  }

  it('keeps steps visible and existing bindings compact until editing', () => {
    expect(fixture.nativeElement.querySelectorAll('.step-card')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.binding-form')).toBeNull();
    expect(fixture.nativeElement.querySelector('.binding-list__summary').textContent).toContain('/accessToken');
    expect(fixture.nativeElement.textContent).toContain('ConsumerProcedureTemplate');
    const edit = fixture.nativeElement.querySelector('.binding-actions button') as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.binding-form textarea')).not.toBeNull();
    expect(harness.form().steps[1].bindingEditor?.expression).toBe('Bearer {{pipe.VALUE}}');
  });

  it('cancels edits without changing the original binding', () => {
    editExisting();
    harness.patchBindingEditor(1, 'pointer', '/changed');
    harness.cancelBindingEditor(1);
    expect(harness.form().steps[1].bindings).toEqual(PIPE.steps[1].bindings);
  });

  it('edits all fields and replaces a binding targeting the same input', () => {
    editExisting();
    harness.patchBindingEditor(1, 'pointer', '/changed');
    harness.patchBindingEditor(1, 'target', 'other');
    harness.patchBindingEditor(1, 'expression', '');
    expect(harness.applyBinding(1)).toBe(true);
    expect(harness.form().steps[1].bindings).toEqual([{ targetParameterKey: 'other', sourceStepKey: 'producer', sourceOutputKey: null, sourceResultPointer: '/changed', valueExpression: null }]);
    harness.openBindingEditor(1);
    harness.patchBindingEditor(1, 'sourceStep', 'producer');
    harness.patchBindingEditor(1, 'pointer', '/replacement');
    harness.patchBindingEditor(1, 'target', 'other');
    expect(harness.applyBinding(1)).toBe(true);
    expect(harness.form().steps[1].bindings).toHaveLength(1);
    expect(harness.form().steps[1].bindings[0].sourceResultPointer).toBe('/replacement');
  });

  it('validates pending bindings before saving and marks the invalid field', async () => {
    editExisting();
    harness.patchBindingEditor(1, 'pointer', 'invalid');
    await harness.save();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(api.update).not.toHaveBeenCalled();
    expect(harness.form().steps[1].bindingEditor?.invalidField).toBe('pointer');
    expect(fixture.nativeElement.querySelector('[data-binding-field="pointer"]').getAttribute('aria-invalid')).toBe('true');
  });

  it('applies valid pending edits on Pipe save and excludes UI metadata from the request', async () => {
    editExisting();
    harness.patchBindingEditor(1, 'pointer', '/pending');
    await harness.save();
    const request = api.update.mock.calls[0][1];
    expect(request.steps[1].bindings[0].sourceResultPointer).toBe('/pending');
    expect(request.steps[1]).not.toHaveProperty('bindingEditor');
    expect(request.steps[1]).not.toHaveProperty('resourceTemplateKey');
    expect(viewport.restore).toHaveBeenCalled();
  });

  it('preserves pending edits when minimizing and restoring a draft', () => {
    editExisting();
    harness.patchBindingEditor(1, 'pointer', '/draft');
    harness.minimizeDraft();
    harness.restoreDraft();
    expect(harness.form().steps[1].bindingEditor?.pointer).toBe('/draft');
    expect(harness.form().steps[1].bindings[0].sourceResultPointer).toBe('/accessToken');
  });

  it('restores older draft fields and recovers missing template metadata from options', () => {
    const form = structuredClone(harness.form());
    const old = { ...form.steps[1], resourceTemplateKey: undefined, bindingEditor: undefined, resultBindingTarget: 'other', resultBindingSourceStep: 'producer', resultBindingPointer: '/old', resultBindingExpression: '{{pipe.VALUE}}' };
    storedDraft = { form: { ...form, steps: [form.steps[0], old] } as unknown as typeof form, editing: PIPE };
    harness.restoreDraft();
    expect(harness.form().steps[1].resourceTemplateKey).toBe('example.ConsumerProcedureTemplate');
    expect(harness.form().steps[1].bindingEditor?.pointer).toBe('/old');
    harness.cancelBindingEditor(1);
    harness.minimizeDraft();
    harness.restoreDraft();
    expect(harness.form().steps[1].bindingEditor).toBeNull();
  });

  it('supports artifacts and clears expressions when changing the binding kind', () => {
    editExisting();
    harness.changeBindingKind(1, 'artifact');
    harness.patchBindingEditor(1, 'target', 'file');
    harness.patchBindingEditor(1, 'artifactSource', 'producer\u0000backup');
    expect(harness.applyBinding(1)).toBe(true);
    expect(harness.form().steps[1].bindings).toEqual([{ targetParameterKey: 'file', sourceStepKey: 'producer', sourceOutputKey: 'backup', sourceResultPointer: null, valueExpression: null }]);
    harness.removeBinding(1, 0);
    expect(harness.form().steps[1].bindings).toEqual([]);
  });

  it('updates pending sources after renaming and rejects forward references after reorder', () => {
    editExisting();
    harness.patchStep(0, 'key', 'renamed');
    expect(harness.form().steps[1].bindingEditor?.sourceStep).toBe('renamed');
    expect(harness.form().steps[1].bindings[0].sourceStepKey).toBe('renamed');
    harness.moveStep(1, -1);
    expect(harness.form().steps[0].bindingEditor?.sourceStep).toBe('renamed');
    expect(harness.applyBinding(0)).toBe(false);
    expect(harness.form().steps[0].bindingEditor?.invalidField).toBe('sourceStep');
  });

  it('resolves historical templates by both resource type and id without guessing missing resources', () => {
    expect(harness.templateKeyFor({ type: 'ALERT', resourceId: 2 })).toBe('example.WebRequestAlertTemplate');
    expect(harness.templateKeyFor({ type: 'PROCEDURE', resourceId: 2 })).toBe('example.ConsumerProcedureTemplate');
    expect(harness.templateKeyFor({ type: 'ALERT', resourceId: 999 })).toBe('');
  });
});
