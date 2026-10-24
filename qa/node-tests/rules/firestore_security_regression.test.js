const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const rules = read('firestore.rules');
const authRepo = read('app/src/main/java/com/example/data/repository/AuthRepository.kt');
const authVm = read('app/src/main/java/com/example/ui/screens/auth/AuthViewModel.kt');
const featureModels = read('app/src/main/java/com/example/data/model/EconomyModels.kt');
const restrictions = read('app/src/main/java/com/example/data/model/UserRestrictions.kt');
const securityManager = read('app/src/main/java/com/example/data/repository/UserSecurityManager.kt');
const pointsRepo = read('app/src/main/java/com/example/data/repository/PointsEarningRepository.kt');
const updateManager = read('app/src/main/java/com/example/data/repository/AppUpdateManager.kt');
const firebaseInitializer = read('app/src/main/java/com/example/utils/FirebaseInitializer.kt');
const application = read('app/src/main/java/com/example/MyApplication.kt');

function blockFor(source, marker) {
  const startAt = source.indexOf(marker);
  assert.notEqual(startAt, -1, `Missing source marker: ${marker}`);
  const open = source.indexOf('{', source.indexOf(' {', startAt) + 1);
  assert.notEqual(open, -1, `No opening brace after: ${marker}`);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}') depth--;
    if (depth === 0) return source.slice(startAt, i + 1);
  }
  throw new Error(`Unclosed source block: ${marker}`);
}

test('Firestore rules retain a final default-deny and have no active comments route', () => {
  assert.match(rules, /match\s*\/\{document=\*\*\}\s*\{\s*allow\s+read\s*,\s*write\s*:\s*if\s+false\s*;/s);
  assert.doesNotMatch(rules, /match\s+\/comments(?:\/|\s*\{)/i);
});

test('public-profile username query is constrained to records visible under current Rules', () => {
  const query = blockFor(authRepo, 'suspend fun checkUsernameAvailability(');
  assert.match(query, /collection\("public_profiles"\)/);
  assert.match(query, /whereEqualTo\("isProfilePublic",\s*true\)/);
  assert.match(query, /whereEqualTo\("username",\s*normalized\)/);
  assert.match(query, /UsernameAvailability\.UNKNOWN/);
  assert.doesNotMatch(query, /catch\s*\([^)]*\)\s*\{[^}]*return\s+false/s);
});

test('username generation has a finite retry limit and refuses UNKNOWN', () => {
  const generator = blockFor(authRepo, 'suspend fun generateUniqueUsername(');
  assert.match(generator, /for\s*\(count\s+in\s+0\.\.99\)/);
  assert.match(generator, /UsernameAvailability\.UNKNOWN\s*->\s*throw IllegalStateException/);
  assert.match(generator, /after 100 attempts/);
});

test('signup does not invent a username or report success when prerequisites fail', () => {
  const signup = blockFor(authVm, 'fun signUpWithEmail(');
  assert.doesNotMatch(signup, /"user_"\s*\+/);
  assert.match(signup, /R\.string\.username_check_failed/);
  assert.match(signup, /R\.string\.failed_save_server/);
  assert.match(signup, /firebaseUser\.delete\(\)\.await\(\)/);
  assert.ok(signup.indexOf('repository.saveUser(newUser)') < signup.indexOf('repository.currentUserFlow.value = newUser'));
});

test('comments are permanently disabled in user models and runtime security gate', () => {
  assert.match(featureModels, /val commentsItem[\s\S]*?status\s*=\s*FeatureState\.DISABLED/);
  assert.match(featureModels, /val isCommentAllowed:\s*Boolean[\s\S]*?get\(\)\s*=\s*false/);
  assert.match(featureModels, /lowercase\(\) in setOf\("comments", "comment", "cancomment"\)/);
  assert.match(restrictions, /val isCommentAllowed:\s*Boolean get\(\) = false/);
  assert.match(securityManager, /fun canComment\(\): Boolean = false/);
  assert.match(securityManager, /R\.string\.comments_feature_disabled/);
});

test('economy remains backend-gated and cannot mint points from the client', () => {
  assert.match(pointsRepo, /TEMPORARY_ECONOMY_MODE\s*=\s*false/);
  assert.match(pointsRepo, /BACKEND_REQUIRED/);
  assert.match(rules, /match\s+\/point_transactions\/\{txId\}[\s\S]*?allow\s+write\s*:\s*if\s+isAdmin\(\)/);
});

test('app updates fail closed for DRAFT and ARCHIVED records', () => {
  assert.match(updateManager, /DRAFT/);
  assert.match(updateManager, /ARCHIVED/);
  assert.match(updateManager, /PUBLISHED/);
});

test('User App initializes the canonical Firebase app before Firebase-backed services start', () => {
  assert.match(firebaseInitializer, /1:979447256418:android:037a9b3a1393e550a9883b/);
  assert.match(firebaseInitializer, /cinestream-sulo/);
  assert.match(firebaseInitializer, /FirebaseApp\.initializeApp\(context, options\)/);
  assert.match(application, /FirebaseInitializer\.initialize\(this\)/);
  assert.ok(application.indexOf('FirebaseInitializer.initialize(this)') < application.indexOf('ManagedExtensionRealtimeSyncManager.getInstance().startListening()'));
});
