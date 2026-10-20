import { describe, expect, it } from 'vitest';

import { extractNumericDatabaseVersion } from './database-version';

describe('extractNumericDatabaseVersion', () => {
  it.each([
    ['Oracle AI Database 26ai Free Release 23.26.3.0.0 - Production', '23.26.3.0.0'],
    ['PostgreSQL 18.6 (Debian)', '18.6'],
    ['8.0.43-0ubuntu0.24.04.2', '8.0.43'],
    ['Microsoft SQL Server 2022 - 16.0.1000.6', '16.0.1000.6'],
    ['release 26ai, then 001.02.003.', '001.02.003'],
    ['123..456, then 7.8', '7.8'],
    ['123.x456.78', '456.78'],
    ['1.2..3.4', '1.2'],
    ['.1.2', '1.2'],
    ['', null],
    ['version 123.', null],
    ['version 123', null],
    ['١.٢', null],
  ])('extracts a version from %s', (value, expected) => {
    expect(extractNumericDatabaseVersion(value)).toBe(expected);
  });

  it('skips a long undotted digit run and still finds a later version', () => {
    const digits = '9'.repeat(100_000);

    expect(extractNumericDatabaseVersion(digits)).toBeNull();
    expect(extractNumericDatabaseVersion(`${digits} Release 18.6`)).toBe('18.6');
  });
});
