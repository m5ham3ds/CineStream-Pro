# PHASE 22.17-A — EXPLICIT GOOGLE SIGN-IN REPAIR REPORT

**Date**: 2026-10-19  
**Target Component**: Explicit Google Sign-In Button Flow & Credential Manager Integration  
**Package Name**: `com.aistudio.cinestream.fmrfyo`  
**Web Client ID**: `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com`  
**Artifact**: `/app/applet/app/build/outputs/apk/debug/app-debug.apk` (Size: ~64 MB)

---

## 1. Executive Summary & Root Cause Analysis

In phase 22.16-A, `WEB_CLIENT_ID` configuration and `CustomCredential` Bundle unwrapping were stabilized. However, on devices with unauthenticated or newly configured Google accounts, the explicit "Sign in with Google" button triggered:
> `No Google accounts available on this device` / `NoCredentialException`

### Root Cause
1. **Flow Incompatibility (`GetGoogleIdOption` vs. `GetSignInWithGoogleOption`)**:
   - `GetGoogleIdOption` is designed primarily for ambient/bottom-sheet One-Tap flows where filtered or previously authorized accounts are queried.
   - For an explicit user click on a "Sign in with Google" button, Google Identity Services and Credential Manager specify `GetSignInWithGoogleOption`. Using `GetSignInWithGoogleOption` prompts the full system account selector dialog and allows account selection or addition even when ambient credentials are not cached.
2. **Credential Type Distinction**:
   - `GetGoogleIdOption` returns credentials with type `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`.
   - `GetSignInWithGoogleOption` returns credentials with type `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL`.
   - The parser required explicit support for both types, alongside nonce handling.
3. **Activity Context Guarantee**:
   - Android Credential Manager requires an active `Activity` context to present the system account selector UI. Invoking it with a generic context wrapper or non-Activity context can result in silent failures or window token exceptions.

---

## 2. Changes Implemented

### A. Explicit Button Flow Migration (`AuthScreen.kt`)
- Dedicated the main "Sign in with Google" button handler `signInWithGoogle()` to `GetSignInWithGoogleOption`:
  ```kotlin
  val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId = webClientId)
      .setNonce(secureNonce)
      .build()

  val request = GetCredentialRequest.Builder()
      .addCredentialOption(signInWithGoogleOption)
      .build()
  ```
- **Cryptographic Nonce**: Generated a fresh, high-entropy 256-bit random SHA-256 hex nonce (`generateSecureNonce()`) for every explicit click, preventing replay attacks and ensuring single-use compliance.
- **Activity Context Enforcement**: Resolved `context.findActivity()` and validated `activity != null` prior to calling `credentialManager.getCredential(activity, request)`.
- **Flow Isolation**: Separated `launchGoogleOneTapBottomSheet()` (which uses `GetGoogleIdOption`) completely from the explicit button flow. Failure or cancellation of the bottom sheet does not block or impair the explicit button click.
- **Privacy & Error Handling**: Handled `NoCredentialException` gracefully by displaying a localized, actionable prompt to the user (`R.string.no_google_accounts_found`), while logging the exception class without logging ID tokens, nonces, or user credentials.

### B. Credential Parsing Support (`GoogleCredentialParser.kt`)
- Added validation for both credential types in `isSupportedGoogleCredentialType`:
  - `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`
  - `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL`
- Maintained strict type safety via `GoogleCredentialResult` (`Success`, `UnexpectedType`, `NotCustomCredential`, `ParsingError`).
- Successfully extracts `idToken`, `id` (email), `displayName`, and `profilePictureUri` from the Bundle payload to pass to Firebase Authentication (`AuthViewModel.handleGoogleSignIn`).

### C. Localization (`strings.xml` & `values-ar/strings.xml`)
- Added `no_google_accounts_found`:
  - **EN**: `"No Google accounts found. Please add or verify a Google account in device Settings and try again."`
  - **AR**: `"لم يتم العثور على حسابات Google على هذا الجهاز. يُرجى إضافة أو تفعيل حساب Google في إعدادات الجهاز ثم المحاولة مجددًا."`

### D. Automated Unit Testing (`GoogleCredentialParserTest.kt`)
- Added tests verifying:
  - Parsing `CustomCredential` with `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL`
  - Malformed empty bundles returning `ParsingError`
  - Minimal SIWG credentials with null optional fields parsing successfully
  - Web client ID validation and non-mock enforcement

---

## 3. Verification & Build Results

All requested Gradle verification tasks were executed sequentially with zero errors:

| Task | Status | Output Summary |
|---|---|---|
| `:app:processDebugGoogleServices` | **SUCCESS** | Configured `com.aistudio.cinestream.fmrfyo` with `google-services.json` |
| `:app:testDebugUnitTest` | **SUCCESS** | 18 unit tests executed and passed (including all SIWG parser tests) |
| `:app:lintDebug` | **SUCCESS** | Lint analysis completed without fatal errors |
| `:app:assembleDebug` | **SUCCESS** | Debug APK built: `app/build/outputs/apk/debug/app-debug.apk` (64 MB) |

---

## 4. Operational Notice Regarding Real-Device Verification

> **CRITICAL OPERATIONAL NOTICE**:  
> While unit tests verify the request building, credential unwrapping, nonce generation, and error mapping, **actual end-to-end Google account authentication MUST be verified on a physical Android device or emulator with Google Play Services installed**.  
> Neither automated unit tests nor compilation tools can simulate live Google Play Services OAuth consent dialogs, account picker sheets, or Firebase Auth credential exchange against the live Google Identity endpoint.  
> Please install the newly generated APK (`app-debug.apk`) on your test device to verify the account picker and sign-in completion.
