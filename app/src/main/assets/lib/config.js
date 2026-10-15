/*
 * config.js — shared settings for the screens bundled in the app.
 * The key must match FCP_API.key in each script's Api.gs (apps-script/<tool>/Api.gs).
 */
window.FCP_CONFIG = {
  key: 'hxOi7cSYfGBZxDLzN6nmMNzoHGuZH5lm',

  // The hub's Apps Script web app (sign-in, users, the list of tools)
  hubUrl: 'https://script.google.com/macros/s/AKfycbwds8wFxInvCWz2AHfayVI6oNRXxMzx9wcT_t85n2CrLkFGoXSS-LC7OUDygYH1_7v5ZQ/exec',

  // Normally a screen gets its script's address from the hub tile it was opened from.
  // These are only used when a screen is opened some other way (e.g. from a notification).
  apps: {
    'ec-viewer': 'https://script.google.com/macros/s/AKfycbykFUgWjq8VlaCL6IFVjro6m812uYruCZO06mlFipD1mPmDuLauoVWtzJitvsyXW1J4/exec',
    'inventory': 'https://script.google.com/macros/s/AKfycbw8muifKQZ-hRBWFdl61Lkx2_jnVa60HQFTOqCBOu9jmnC6blxaI5q7wz-eJfRUfgxTAA/exec'
  }
};
