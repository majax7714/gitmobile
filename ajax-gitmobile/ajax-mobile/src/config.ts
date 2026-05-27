// Central configuration. The relay host can be overridden at build time with
// VITE_RELAY_HOST; otherwise it defaults to the production relay.
const DEFAULT_RELAY_HOST = 'ajax-relay.kb3tonline.com';

export const RELAY_HOST = import.meta.env.VITE_RELAY_HOST ?? DEFAULT_RELAY_HOST;

export const API_BASE = `https://${RELAY_HOST}/api`;
export const WS_URL = `wss://${RELAY_HOST}/api/shell`;

/** Heartbeat cadence while foregrounded with an active shell. */
export const HEARTBEAT_INTERVAL_MS = 30_000;

/** Max time to wait for the EC2 box to report RUNNING after a wake. */
export const COLD_START_TIMEOUT_MS = 120_000;

/** Status poll cadence (drives the status pill + wake progress). */
export const STATUS_POLL_INTERVAL_MS = 3_000;

/** Preferences key under which the bearer token is stored. */
export const TOKEN_STORAGE_KEY = 'relay.bearerToken';
