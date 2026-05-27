/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Relay backend hostname (no scheme), e.g. ajax-relay.kb3tonline.com */
  readonly VITE_RELAY_HOST?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
