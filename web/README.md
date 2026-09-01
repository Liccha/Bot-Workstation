# Bot Editor Web

Static Editor UI plus shared Node API handlers for the public song browser, cloud library, mobile devices and authenticated administration workflow.

Production separates hosting from data processing: Vercel serves the static site, while `fc/index.js` adapts the same handlers to Alibaba Cloud Function Compute for the primary domestic API route. Metadata, media and compact library snapshots are stored in OSS and delivered through CDN where appropriate.

## Development

```bash
npm ci
npm test
```

The test suite covers both the handler contract and the Function Compute event adapter. A deployable FC source bundle can be assembled with:

```powershell
npm run build:fc
```

Copy `.env.example` to a local `.env` only when exercising cloud-backed handlers. Never place access keys, administrator tokens, or production allowlists in committed files. Browser origins and preview hosts are configured through environment variables.
