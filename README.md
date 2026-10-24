# CineStream User App — Project Layout

هذا الفهرس يوضح أماكن الملفات حتى تبقى ملفات التطبيق الفعلية منفصلة عن أدوات الفحص والتوثيق التاريخي.

## ملفات تشغيل وإعدادات أساسية — أبقها في الجذر
- `app/`: مصدر تطبيق Android. ملف Google Services النشط هو `app/google-services.json`.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew`, `gradlew.bat`: إعدادات Gradle والبناء.
- `firebase.json`, `.firebaserc`, `firestore.rules`: إعداد Firebase CLI؛ الملف الذي يحدده `firebase.json` هو `firestore.rules` في الجذر.
- `debug.keystore`: شهادة Debug المحلية. لا تستخدمها بدل شهادة Release.
- `.env.example`, `package.json`, `server.js`, `metadata.json`: إعدادات/أدوات المشروع التي بقيت في مواضعها لتفادي كسر البيئة.
- `backend/`: قالب Worker مستقبلي منفصل وغير مفعّل؛ الهجرة إلى Cloudflare غير معتمدة، لذا يجب أن تبقى `BACKEND_ENABLED=false`.

## هوية Firebase
- `app/google-services.json`: ملف الإعداد الفعلي الذي يقرأه Google Services Gradle Plugin.
- `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json`: مرجع هوية تستخدمه فحوص الهوية المساعدة؛ ليس بديلًا لملف Google Services.

## الفحص والاختبارات
- `qa/node-tests/identity/` و`qa/node-tests/rules/`: أدوات Node المساعدة، وأوامرها محددة في `package.json`.
- `app/src/test/` و`app/src/androidTest/`: اختبارات Android في مسارات Gradle القياسية؛ يجب أن تبقى داخل `app/src`.
- لم تُشغّل اختبارات أو Gradle أثناء عملية التنظيم.

## التوثيق والمرجع
- `docs/archive/phase-reports/`: تقارير المراحل السابقة.
- `docs/firebase/contracts/`: عقود البيانات المشتركة.
- `docs/firebase/audits/`: تقارير تدقيق Firebase/Firestore.
- `docs/firebase/rules-history/candidates/`: مسودات قديمة للقواعد غير النشطة.
- `docs/firebase/rules-history/snapshots/`: نسخ مرجعية تاريخية من القواعد.
- `docs/legacy/`: إعدادات قديمة محفوظة للمرجع فقط.
- `docs/FILE_ORGANIZATION_MANIFEST.md`: سجل الملفات التي نُقلت ومساراتها الجديدة.

**تنبيه:** ملفات `docs/firebase/rules-history/` مرجعية فقط وليست أهداف نشر. لا تنشر قواعد Firestore دون مراجعة واعتماد منفصلين.
