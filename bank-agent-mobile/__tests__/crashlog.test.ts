import { clearCrashLog, getCrashLog, logError } from '../src/lib/crashlog';

describe('local crash log', () => {
  beforeEach(async () => {
    await clearCrashLog();
  });

  it('records and reads errors', async () => {
    await logError(new Error('boom'), 'render');
    const entries = await getCrashLog();
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('boom');
    expect(entries[0].context).toBe('render');
  });

  it('clears entries', async () => {
    await logError('oops');
    await clearCrashLog();
    expect(await getCrashLog()).toEqual([]);
  });
});
