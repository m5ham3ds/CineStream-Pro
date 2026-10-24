# File Organization Manifest / سجل تنظيم الملفات

Project: CineStream User App
Action: file organization only. No tests, Gradle tasks, emulator, or Firebase deployment were run.

## Root files kept in place because runtime/build/deploy configuration depends on them
- `firebase.json`, `.firebaserc`, `firestore.rules`, Gradle wrapper/configuration, and the Android `app/` tree.
- `app/google-services.json` remains the Android Google Services configuration.
- Android unit/instrumentation tests remain in the Gradle-standard `app/src/test` and `app/src/androidTest` locations.

## Moved items
- `CINESTREAM_FIREBASE_IDENTITY_LOCK.json` → `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json`
- `CINESTREAM_PRO firestore.rules.txt` → `docs/firebase/rules-history/snapshots/CINESTREAM_PRO firestore.rules.txt`
- `FIREBASE_LINKAGE_AUDIT.md` → `docs/firebase/FIREBASE_LINKAGE_AUDIT.md`
- `PHASE_22.14_USER_APP_CONTRACT_RECONCILIATION_REPORT.md` → `docs/archive/phase-reports/PHASE_22.14_USER_APP_CONTRACT_RECONCILIATION_REPORT.md`
- `PHASE_22.15-A_USER_APP_FIREBASE_IDENTITY_AND_SIGNING_REPORT.md` → `docs/archive/phase-reports/PHASE_22.15-A_USER_APP_FIREBASE_IDENTITY_AND_SIGNING_REPORT.md`
- `PHASE_22.16-A_USER_APP_GOOGLE_SIGNIN_FIX_REPORT.md` → `docs/archive/phase-reports/PHASE_22.16-A_USER_APP_GOOGLE_SIGNIN_FIX_REPORT.md`
- `PHASE_22.17-A_EXPLICIT_GOOGLE_SIGN_IN_REPAIR_REPORT.md` → `docs/archive/phase-reports/PHASE_22.17-A_EXPLICIT_GOOGLE_SIGN_IN_REPAIR_REPORT.md`
- `PHASE_22.19-A_ACCOUNT_IDENTITY_FORENSIC_REPORT.md` → `docs/archive/phase-reports/PHASE_22.19-A_ACCOUNT_IDENTITY_FORENSIC_REPORT.md`
- `PHASE_22.19-A_ACCOUNT_RECOVERY_AND_SESSION_FIX_REPORT.md` → `docs/archive/phase-reports/PHASE_22.19-A_ACCOUNT_RECOVERY_AND_SESSION_FIX_REPORT.md`
- `PHASE_22.21-A_USER_APP_FIRESTORE_CONTRACT.md` → `docs/firebase/contracts/PHASE_22.21-A_USER_APP_FIRESTORE_CONTRACT.md`
- `PHASE_22.21-A_USER_APP_FIRESTORE_RULES_AUDIT_REPORT.md` → `docs/firebase/audits/PHASE_22.21-A_USER_APP_FIRESTORE_RULES_AUDIT_REPORT.md`
- `PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules` → `docs/firebase/rules-history/candidates/PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules`
- `PHASE_22.22-A_USER_APP_FIRESTORE_RULES_UPDATE_REPORT.md` → `docs/archive/phase-reports/PHASE_22.22-A_USER_APP_FIRESTORE_RULES_UPDATE_REPORT.md`
- `PHASE_22.22-C_USER_COMPATIBILITY_AUDIT_REPORT.md` → `docs/firebase/audits/PHASE_22.22-C_USER_COMPATIBILITY_AUDIT_REPORT.md`
- `PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules` → `docs/firebase/rules-history/candidates/PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules`
- `PHASE_22.22-D_USER_APP_COMPATIBILITY_REPAIR_REPORT.md` → `docs/archive/phase-reports/PHASE_22.22-D_USER_APP_COMPATIBILITY_REPAIR_REPORT.md`
- `PHASE_22.23_CENTRAL_UNIFIED_RULES_CANDIDATE.rules.txt` → `docs/firebase/rules-history/candidates/PHASE_22.23_CENTRAL_UNIFIED_RULES_CANDIDATE.rules.txt`
- `PHASE_22.23_CINESTREAM_REPAIR_AND_TEST_REPORT_AR.md` → `docs/archive/phase-reports/PHASE_22.23_CINESTREAM_REPAIR_AND_TEST_REPORT_AR.md`
- `PHASE_22.24_FIREBASE_IDENTITY_AND_GOOGLE_SIGNIN_FIX_REPORT_AR.md` → `docs/archive/phase-reports/PHASE_22.24_FIREBASE_IDENTITY_AND_GOOGLE_SIGNIN_FIX_REPORT_AR.md`
- `PHASE_22.25_POST_REPORT_FIXES_AR.md` → `docs/archive/phase-reports/PHASE_22.25_POST_REPORT_FIXES_AR.md`
- `Rules.md` → `docs/firebase/rules-history/snapshots/Rules.md`
- `firestore.rules.candidate.txt` → `docs/firebase/rules-history/candidates/firestore.rules.candidate.txt`
- `tests/identity/firebase_identity_integrity.test.js` → `qa/node-tests/identity/firebase_identity_integrity.test.js`
- `tests/rules/firestore_security_regression.test.js` → `qa/node-tests/rules/firestore_security_regression.test.js`

## Safety scope
- No Android application Kotlin/Java/XML source was changed for organization.
- QA file paths and links were updated where necessary after relocation; no suites were executed.
- No Firebase rules were deployed and no production database was changed.
- Historical reports, candidate rules, snapshots, and backups were moved, not deleted.
