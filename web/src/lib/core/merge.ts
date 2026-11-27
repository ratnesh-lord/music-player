// Mirrors android/core SyncMerge.kt (shared/test-vectors/sync-merge.json).
export interface SyncStamp { id: string; updatedAt: number; deleted: boolean; deviceId: string }
export interface SyncPlan { pull: string[]; push: string[] }

export function winner(a: SyncStamp, b: SyncStamp): SyncStamp {
  if (a.updatedAt !== b.updatedAt) return a.updatedAt > b.updatedAt ? a : b;
  if (a.deleted !== b.deleted) return a.deleted ? a : b;
  return a.deviceId >= b.deviceId ? a : b;
}

export function plan(local: SyncStamp[], remote: SyncStamp[]): SyncPlan {
  const l = new Map(local.map((s) => [s.id, s]));
  const r = new Map(remote.map((s) => [s.id, s]));
  const ids = [...new Set([...l.keys(), ...r.keys()])].sort((x, y) => (x < y ? -1 : x > y ? 1 : 0));
  const pull: string[] = [];
  const push: string[] = [];
  for (const id of ids) {
    const a = l.get(id);
    const b = r.get(id);
    if (!a) pull.push(id);
    else if (!b) push.push(id);
    else if (a.updatedAt === b.updatedAt && a.deleted === b.deleted && a.deviceId === b.deviceId) continue;
    else if (winner(a, b) === b) pull.push(id);
    else push.push(id);
  }
  return { pull, push };
}
