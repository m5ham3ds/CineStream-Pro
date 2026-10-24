# Legacy Google AI Studio applet Firebase config

This file was preserved from the supplied project as `firebase-applet-config.previous-project.json`. It points to a different Firebase project (`ai-studio-applet-webapp-71606`, project number `406285499532`) and is **not** the Android Firebase configuration for CineStream.

The Android app must use `app/google-services.json`, whose canonical project is `cinestream-sulo` (project number `979447256418`). No replacement Web App config was fabricated because a registered Web App's own Firebase App ID cannot be derived from the Android JSON. If the Google AI Studio web applet itself must connect to `cinestream-sulo`, obtain its official Web SDK configuration from that project's Firebase Console first.
