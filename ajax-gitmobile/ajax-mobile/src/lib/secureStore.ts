import { Preferences } from '@capacitor/preferences';
import { TOKEN_STORAGE_KEY } from '../config';

// Thin wrapper around Capacitor Preferences for the bearer token. Swap the
// implementation here for @capacitor-community/secure-storage if a
// Keychain/Keystore-backed store is required — the call sites don't change.

export async function getToken(): Promise<string | null> {
  const { value } = await Preferences.get({ key: TOKEN_STORAGE_KEY });
  return value ?? null;
}

export async function setToken(token: string): Promise<void> {
  await Preferences.set({ key: TOKEN_STORAGE_KEY, value: token });
}

export async function clearToken(): Promise<void> {
  await Preferences.remove({ key: TOKEN_STORAGE_KEY });
}
