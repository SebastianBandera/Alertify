/** Finds the first dotted numeric version without retrying matches within a digit run. */
export function extractNumericDatabaseVersion(value: string): string | null {
  const isDigit = (index: number): boolean => index < value.length && value[index] >= '0' && value[index] <= '9';
  let index = 0;
  while (index < value.length) {
    if (!isDigit(index)) {
      index += 1;
      continue;
    }

    const start = index;
    while (isDigit(index)) index += 1;

    let dotted = false;
    while (value[index] === '.' && isDigit(index + 1)) {
      dotted = true;
      index += 1;
      while (isDigit(index)) index += 1;
    }
    if (dotted) return value.slice(start, index);
  }
  return null;
}
