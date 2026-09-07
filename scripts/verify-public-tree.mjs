import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ignoredDirectories = new Set(['.git', '.idea', '.dart_tool', '.gradle', 'build', 'ephemeral', 'target', 'node_modules', 'coverage']);
const forbiddenNames = [
  /^\.env(?:\..+)?$/i,
  /^local\.properties$/i,
  /^key\.properties$/i,
  /^admin_(?:password|devices|blocked_ips)\.json$/i,
  /^admin_password\.txt$/i,
  /credentials.*\.properties$/i,
  /\.(?:db|sqlite3?|jks|keystore|p12|pem|key|apk|aab|exe|msi)$/i,
];
const textExtensions = new Set(['.java', '.dart', '.js', '.mjs', '.json', '.html', '.css', '.xml', '.yaml', '.yml', '.properties', '.md', '.txt']);
const forbiddenContent = [
  { label: 'Aliyun AccessKey ID', pattern: /LTAI[A-Za-z0-9]{12,}/ },
  { label: 'private key', pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { label: 'absolute Windows user path', pattern: /[A-Za-z]:\\Users\\(?!example\\)/i },
  { label: 'filled secret assignment', pattern: /(?:ACCESS_KEY_SECRET|SESSION_SECRET|BOT_TOKEN|DESKTOP_TOKEN)\s*=\s*(?!<|$|change-me|example)[^\s#]{8,}/i },
];

const productionRuntimePatterns = [
  { label: 'production control-plane hostname', pattern: /songbotstic-api-cwpfgfkkpj\.cn-beijing\.fcapp\.run/i },
  { label: 'production Editor control origin', pattern: /(?:editor\.teacharm\.moe|bot-editor\.vercel\.app)/i },
  { label: 'production desktop update feed', pattern: /assets\.teacharm\.moe\/bot-workstation\/releases/i },
  { label: 'production mobile update feed', pattern: /assets\.teacharm\.moe\/bot-workstation\/mobile/i },
];

function isRuntimeSource(relative) {
  return /^(?:mczmaker|mobile|songbot|web|workstation)\/(?!tests?\/|.*\/tests?\/)/.test(relative)
    && !relative.endsWith('.md');
}

const failures = [];
const requiredReadOnlyGuards = [
  ['workstation/src/main/java/com/botstation/BotStationApp.java', /portfolioSnapshot\(\)/],
  ['mczmaker/src/main/java/com/mcz/MczTool.java', /portfolioSnapshot\(\)/],
  ['songbot/src/main/java/com/mybot/SongBot.java', /portfolioSnapshot\(\)/],
  ['mobile/lib/main.dart', /_portfolioSnapshot\(\)/],
  ['web/index.html', /PORTFOLIO_READ_ONLY=true/],
  ['web/api/_lib/showcase-policy.js', /portfolio showcase is read-only/],
];
function visit(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) continue;
    const full = path.join(directory, entry.name);
    const relative = path.relative(root, full).replaceAll('\\', '/');
    if (entry.isDirectory()) {
      visit(full);
      continue;
    }
    if (forbiddenNames.some(pattern => pattern.test(entry.name)) && entry.name !== '.env.example') {
      failures.push(`${relative}: forbidden local/secret file`);
      continue;
    }
    if (!textExtensions.has(path.extname(entry.name).toLowerCase())) continue;
    const content = fs.readFileSync(full, 'utf8');
    for (const rule of forbiddenContent) {
      if (rule.label === 'filled secret assignment' && relative.includes('/tests/')) continue;
      if (rule.pattern.test(content)) failures.push(`${relative}: ${rule.label}`);
    }
    if (isRuntimeSource(relative)) {
      for (const rule of productionRuntimePatterns) {
        if (rule.pattern.test(content)) failures.push(`${relative}: ${rule.label}`);
      }
    }
  }
}

visit(root);
for (const [relative, pattern] of requiredReadOnlyGuards) {
  const full = path.join(root, relative);
  if (!fs.existsSync(full) || !pattern.test(fs.readFileSync(full, 'utf8'))) {
    failures.push(`${relative}: missing portfolio read-only guard`);
  }
}
if (failures.length) {
  console.error('Public-tree verification failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}
console.log('PUBLIC_TREE_GREEN');
