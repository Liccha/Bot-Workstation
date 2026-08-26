# Bot Editor Web

Static editor UI plus serverless API handlers used by the public song browser and the authenticated administration workflow.

## Development

```bash
npm ci
npm test
```

Copy `.env.example` to a local `.env` only when exercising cloud-backed handlers. Never place access keys, administrator tokens, or production allowlists in committed files. Browser origins and preview hosts are configured through environment variables.
