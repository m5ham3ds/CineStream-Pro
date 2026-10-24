const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../..');
const lock = JSON.parse(fs.readFileSync(path.join(root, 'config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json'), 'utf8'));
const servicePath = path.join(root, 'app/google-services.json');
const services = JSON.parse(fs.readFileSync(servicePath, 'utf8'));
const expected = lock.applications.user;

test('Firebase project, app ID, package, and JSON fingerprint match the identity lock', () => {
  assert.equal(services.project_info.project_id, lock.firebase.projectId);
  assert.equal(services.project_info.project_number, lock.firebase.projectNumber);
  const actualJsonSha = crypto.createHash('sha256').update(fs.readFileSync(servicePath)).digest('hex');
  assert.equal(actualJsonSha, lock.firebase.googleServicesJsonSha256);
  const client = services.client.find(c => c.client_info.android_client_info.package_name === expected.applicationId);
  assert.ok(client, `Missing registered Android client for ${expected.applicationId}`);
  assert.equal(client.client_info.mobilesdk_app_id, expected.firebaseAppId);
  const gradle = fs.readFileSync(path.join(root, 'app/build.gradle.kts'), 'utf8');
  assert.ok(gradle.includes('applicationId = "' + expected.applicationId + '"'));
});

test('Android OAuth client and certificate SHA-1 match the identity lock', () => {
  const client = services.client.find(c => c.client_info.android_client_info.package_name === expected.applicationId);
  const oauth = client.oauth_client.find(o => o.client_type === 1);
  assert.ok(oauth);
  assert.equal(oauth.client_id, expected.androidOauthClientId);
  assert.equal(oauth.android_info.certificate_hash.toLowerCase(), expected.debugSha1);
  const result = spawnSync('keytool', ['-list', '-v', '-keystore', path.join(root, 'debug.keystore'), '-storepass', lock.android.debugKeystorePassword, '-alias', lock.android.debugKeystoreAlias], {encoding:'utf8'});
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const sha1 = result.stdout.match(/SHA1:\s*([0-9A-F:]+)/i);
  const sha256 = result.stdout.match(/SHA256:\s*([0-9A-F:]+)/i);
  assert.ok(sha1, 'keytool did not report a SHA-1 fingerprint');
  assert.ok(sha256, 'keytool did not report a SHA-256 fingerprint');
  assert.equal(sha1[1].replaceAll(':','').toLowerCase(), expected.debugSha1);
  assert.equal(sha256[1].replaceAll(':','').toLowerCase(), expected.debugSha256);
});

test('Web OAuth client matches the shared server client ID', () => {
  const client = services.client.find(c => c.client_info.android_client_info.package_name === expected.applicationId);
  assert.ok(client.oauth_client.some(o => o.client_type === 3 && o.client_id === lock.firebase.webOauthClientId));
});


test('User App CI writes the exact locked google-services.json bytes', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/build.yml'), 'utf8');
  const match = workflow.match(/echo\s+"([A-Za-z0-9+/=]{2000,})"\s*\|\s*base64\s+-d\s*>\s*app\/google-services\.json/);
  assert.ok(match, 'CI workflow no longer embeds the official google-services.json payload');
  const embedded = Buffer.from(match[1], 'base64');
  assert.deepEqual(embedded, fs.readFileSync(servicePath), 'CI payload diverges from app/google-services.json');
});


test('Build configuration resolves the Web OAuth client from google-services.json and normalizes mock environment values', () => {
  const gradle = fs.readFileSync(path.join(root, 'app/build.gradle.kts'), 'utf8');
  assert.match(gradle, /JsonSlurper\(\)\.parse\(googleServicesFile\)/);
  assert.match(gradle, /isPlaceholderWebClientId\(suppliedWebClientId\)/);
  assert.match(gradle, /expectedWebClientId\.set\(canonicalWebClientId\)/);
  assert.match(gradle, /currentValue\.isBlank\(\)/);
  assert.match(gradle, /requiredBuildConfigKeys/);
});
