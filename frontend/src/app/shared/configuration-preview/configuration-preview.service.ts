import { Injectable, Injector, inject, signal } from '@angular/core';

import { ApplicationConfiguration, ConfigurationApiService } from '../../core/api/configuration-api.service';

@Injectable({ providedIn: 'root' })
export class ConfigurationPreviewService {
  private readonly injector = inject(Injector);
  private readonly requests = new Map<number, Promise<string>>();
  readonly revision = signal(0);

  clear(): void {
    this.requests.clear();
    this.revision.update((value) => value + 1);
  }

  get(id: number): Promise<string> {
    const existing = this.requests.get(id);
    if (existing) return existing;

    const request = this.injector.get(ConfigurationApiService).getConfiguration(id).then((configuration) => this.resolve(configuration));
    this.requests.set(id, request);
    void request.catch(() => {
      if (this.requests.get(id) === request) this.requests.delete(id);
    });
    return request;
  }

  async resolve(configuration: ApplicationConfiguration): Promise<string> {
    if (configuration.valueType === 'EXPRESSION') {
      const result = await this.injector.get(ConfigurationApiService).evaluateExpression({
        configurationId: configuration.id,
        configurationName: configuration.name,
        expression: String(configuration.value),
      });
      return result.value;
    }
    if (configuration.valueType === 'BINARY')
      return `${configuration.binaryFileName ?? 'binary'} (${configuration.binarySize ?? 0} B)`;

    return configuration.valueType === 'JSON' ? JSON.stringify(configuration.value) : String(configuration.value ?? '');
  }
}
