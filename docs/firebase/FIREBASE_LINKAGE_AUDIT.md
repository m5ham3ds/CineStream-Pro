# Firebase linkage — CineStream User App

This file documents the *current repository configuration* after the PHASE 22.27 static reconciliation. It is not evidence of live Firebase Console state.

- Firebase project ID: `cinestream-sulo`
- Project number / messaging sender ID: `979447256418`
- Realtime Database URL recorded in JSON: `https://cinestream-sulo-default-rtdb.firebaseio.com`
- Storage bucket recorded in JSON: `cinestream-sulo.firebasestorage.app`
- Android application ID: `com.aistudio.cinestream.xyzabc`
- Firebase Android App ID: `1:979447256418:android:037a9b3a1393e550a9883b`
- Android OAuth client ID: `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com`
- Shared Web OAuth client ID: `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com`
- `app/google-services.json` SHA-256: `9bdb880b9e47424f8ce833de7631967ff3b37c84795b6d3075e7faa8e390d895`
- `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json` SHA-256: `f6f31e7f949f62e5cc901a53c3d8dcb28bf180787b575f5ac2532b4d7f4cfe2f`
- `firestore.rules` SHA-256: `6ef81a3f3bcf08aacf7ac59f1b74dbbc249001a2aefb66fb4a3635cd043b89e4`
- Firebase CLI default alias: `cinestream-sulo`

## Single source of truth

`firebase.json` points to `firestore.rules`. That is the only rules file directly selected for Firebase CLI deployment. Files named `*.candidate*`, `Rules.md`, and phase reports are history/reference artifacts and are **not** evidence of the live deployed Ruleset. Do not deploy until the intended rules have been explicitly reviewed and approved.

## Not confirmed from repository files

- Whether Google sign-in is enabled in Firebase Authentication for this project.
- Whether the OAuth clients, authorized domains, and SHA-1 values are registered in the current Google/Firebase Console state.
- The Release signing certificate, because the Release keystore is not included.
- Any App Check enforcement or API-key restrictions enabled in Console.
- Whether Storage / Realtime Database are used in production; the JSON contains those endpoints, but active Android source review found no direct Firebase Storage or Realtime Database SDK operation.
