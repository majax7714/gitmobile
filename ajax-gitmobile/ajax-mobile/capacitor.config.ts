import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ajax.relay.mobile',
  appName: 'Ajax Relay',
  webDir: 'dist',
  server: {
    // Relay uses a valid Let's Encrypt cert, so https/wss work without
    // cleartext exceptions on either platform.
    androidScheme: 'https',
  },
};

export default config;
