import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ajax.relay.mobile',
  appName: 'Ajax Relay',
  webDir: 'dist',
  server: {
    // Relay uses a valid Let's Encrypt cert, so https/wss work without
    // cleartext exceptions on either platform.
    androidScheme: 'https',
    // Terminal output can contain arbitrary URLs; without an allow-list a
    // stray tap could navigate the WebView away from the app shell. Only the
    // relay's own origin is allowed; external URLs are routed through the
    // system browser via @capacitor/browser instead.
    allowNavigation: ['ajax-relay.kb3tonline.com'],
  },
};

export default config;
