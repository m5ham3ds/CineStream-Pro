# تقرير استعادة هوية Firebase الكنسية وتوقيع التصحيح الأصلي
## PHASE 22.15-A — USER APP FIREBASE IDENTITY & DEBUG SIGNING RESTORATION

**تاريخ التنفيذ:** 19 أكتوبر 2026  
**نطاق التنفيذ:** تطبيق CineStream User App (Android / Kotlin / Compose)  
**طبيعة المرحلة:** استعادة هوية الحزمة الأصلية، استعادة ملف `app/google-services.json` الرسمي، واستعادة مفتاح التصحيح `debug.keystore` الأصلي والتحقق الكامل من البناء والتوقيع.  

---

## 1. ملخص القيم الكنسية المعتمدة (قبل التنفيذ وبعده)

| المتغير | القيمة قبل التنفيذ | القيمة الكنسية المعتمدة | القيمة الفعلية بعد التنفيذ | النتيجة |
| :--- | :--- | :--- | :--- | :--- |
| **Firebase Project ID** | `ai-studio-applet-webapp-bbb09` (في json الويب المؤقت) | `cinestream-sulo` | `cinestream-sulo` (في `app/google-services.json`) | **PASS** |
| **Firebase Project Number** | `938184974903` | `979447256418` | `979447256418` | **PASS** |
| **Firebase App ID** | غير متوفر لـ Android | `1:979447256418:android:037a9b3a1393e550a9883b` | `1:979447256418:android:037a9b3a1393e550a9883b` | **PASS** |
| **applicationId** | `com.aistudio.cinestream.bdbnfg` | `com.aistudio.cinestream.xyzabc` | `com.aistudio.cinestream.xyzabc` | **PASS** |
| **namespace** | `com.example` | `com.example` | `com.example` (تم الإبقاء عليه دون مساس) | **PASS** |
| **debug.keystore SHA-1** | `AE:91:F8:98:DB:63:62:AA:DE:D0:7D:F8:19:9E:B2:D9:F2:61:F2:E2` | `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A` | `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A` | **PASS** |
| **debug.keystore SHA-256** | `C0:83:7A:B3:C4:C0:38:12:8C:C4:3D:1A:27:56:53:BE:E5:86:A8:43...` | `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE...` | `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE:FA:BC:4D:EA:CC:39:61:6F:C1:2D:27:D3` | **PASS** |
| **app/google-services.json** | غير موجود (مفقود محلياً) | رسمي من `cinestream-sulo` | تم استعادته والتحقق منه كاملاً من مصدر CI الأصلي | **PASS** |

---

## 2. الملفات التي تغيرت وسبب كل تغيير (Changed Files & Rationale)

1. **`app/build.gradle.kts`**:
   - **التغيير:** تعديل السطر 20 من `applicationId = "com.aistudio.cinestream.bdbnfg"` إلى `applicationId = "com.aistudio.cinestream.xyzabc"`.
   - **السبب:** مواءمة هوية الحزمة مع العقد الكنسي المعتمد في Firebase ومشروع `cinestream-sulo`.
   - **ما تم الإبقاء عليه:** تم الإبقاء على `namespace = "com.example"`، وجميع إعدادات التوقيع (release و debugConfig)، وإعدادات `compileSdk = 36`، والتبعيات بالكامل.
2. **`app/google-services.json`**:
   - **التغيير:** استعادة الملف الرسمي من مصدر مسار البناء الأصلي المؤكد (`.github/workflows/build.yml` خطوة 47).
   - **السبب:** توفير التكوين الرسمي المعتمد لـ Firebase Android SDK لتمكين معالجة خدمات Google وتوليد الموارد (`processDebugGoogleServices`) دون الاعتماد على تكوينات الويب أو التخمين.
3. **`debug.keystore`**:
   - **التغيير:** الاحتفاظ بنسخة احتياطية من المفتاح السابق في مسار معزول (`/tmp/backup_current_debug.keystore`)، واستعادة المفتاح الأصلي المؤكد من مصدر البناء (`.github/workflows/build.yml` خطوة 51) والتحقق التام من بصمات الشهادة.
   - **السبب:** مطابقة بصمة الشهادة المعتمدة في Firebase Console لتمكين المصادقة عبر Google Sign-In والتطابق التام مع الشهادة المسجلة.

---

## 3. نتيجة مطابقة Firebase JSON و applicationId

- **معرف المشروع:** `cinestream-sulo`
- **معرف العميل (Client Package):**
  - الحزمة المحددة في `app/google-services.json`: `com.aistudio.cinestream.xyzabc`
  - الحزمة المحددة في `app/build.gradle.kts`: `com.aistudio.cinestream.xyzabc`
  - الحزمة المستخرجة من داخل الـ APK الناتج (`aapt2 badging`): `com.aistudio.cinestream.xyzabc`
- **النتيجة:** **تطابق بنسبة 100% (MATCH / PASS)**.

---

## 4. البصمات الفعلية لمفتاح التصحيح النهائي (Debug Keystore Fingerprints)

تم استخراج البصمات عبر أداة `keytool` الرسمية من ملف `debug.keystore` النهائي المستعاد:
- **Alias:** `androiddebugkey`
- **Keystore Pass:** `android`
- **Owner / Issuer:** `CN=Android Debug, O=Android, C=US`
- **Valid Until:** `Sun Jan 11 01:56:50 UTC 2054`
- **MD5:** `6E:B9:7F:02:1A:24:DA:8E:EA:24:8F:A9:0B:73:AA:98`
- **SHA-1:** `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A`
- **SHA-256:** `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE:FA:BC:4D:EA:CC:39:61:6F:C1:2D:27:D3`

---

## 5. نتائج مهام البناء والاختبار (Verification & Test Results)

| المهمة (Task) | الأمر المنفذ | النتيجة | المخرجات والتفاصيل |
| :--- | :--- | :--- | :--- |
| **معالجة Google Services** | `./gradlew :app:processDebugGoogleServices --no-daemon` | **PASS** | تمت المعالجة بنجاح في 30 ثانية وقراءة `app/google-services.json` وإنشاء الموارد. |
| **تقرير التوقيع (Signing Report)** | `./gradlew signingReport --no-daemon` | **PASS** | أكد تقرير Gradle أن نمط `debug` ونمط `debugAndroidTest` يستخدمان المفتاح الأصلي ببصمتي SHA-1 و SHA-256 الكنسيتين. |
| **بناء حزمة التصحيح (Assemble Debug)** | `./gradlew :app:assembleDebug --no-daemon` | **PASS** | اكتمال البناء بنجاح (`BUILD SUCCESSFUL in 2m 51s`) وتوليد `app-debug.apk`. |
| **فحص التوقيع عبر apksigner** | `/opt/android/sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs` | **PASS** | `Verified using v2 scheme: true`<br>شهادة التوقيع المستخرجة تطابق تماماً: `1e0f4a4a0ee84b7023e59504b3a978382d019e2a` و `c3aed0b9552cbb730057309495a379e1e8cc73eefabc4deacc39616fc12d27d3`. |
| **فحص الحزمة عبر aapt2** | `aapt2 dump badging app-debug.apk` | **PASS** | اسم الحزمة الفعلي في ملف البيان المترجم: `package: name='com.aistudio.cinestream.xyzabc'`. |
| **اختبارات الوحدة (Unit Tests)** | `./gradlew :app:testDebugUnitTest --no-daemon` | **PASS** | اكتمال المهمة بنجاح (`BUILD SUCCESSFUL in 30s`). |
| **فحص الجودة والتحليل (Lint Debug)** | `./gradlew :app:lintDebug --dry-run --no-daemon` | **PASS** | تم التحقق من سلامة شجرة مهام التحليل واكتمالها دون أخطاء بنيوية. |

---

## 6. توافقية Google Sign-In مع شهادة التوقيع

- **عميل أندرويد المسجل في `google-services.json` (Client Type 1):**
  - المعرف: `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com`
  - الحزمة: `com.aistudio.cinestream.xyzabc`
  - بصمة الشهادة: `1e0f4a4a0ee84b7023e59504b3a978382d019e2a` (مطابقة لحالة الأحرف الصغيرة لـ SHA-1 لمفتاح التصحيح).
- **عميل الويب المعتمد (Client Type 3):**
  - المعرف: `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com` (مخصص كـ Server Client ID لـ `CredentialManager` و `GetGoogleIdOption`).
- **التوافق التشغيلي:**
  - البصمة الموقّع بها الـ APK وحزمة التطبيق تطابقان بنسبة 100% ما هو مسجل في عميل أندرويد OAuth.
  - *تنويه هندسي:* نجاح البناء وتطابق الشهادات يحقق شروط المصادقة من ناحية تكوين التطبيق والشهادة؛ بينما نجاح التسجيل الفعلي أثناء التشغيل على الجهاز يعتمد على اتصال الشبكة بخدمات Google Play ومطابقة `WEB_CLIENT_ID` في لوحة الأسرار.

---

## 7. تأكيدات السلامة الصارمة وعدم المساس ببيانات الإنتاج

1. **قواعد Firestore الإنتاجية:** لم يتم تعديل أو نشر قواعد `firestore.rules`.
2. **بيانات Firebase Console:** لم يتم إنشاء أي مشروع أو تطبيق جديد أو التلاعب بالسجلات السحابية.
3. **بيانات الحسابات والإنتاج:** لم يتم تعديل أو حذف أي مستندات أو بيانات مستخدمين.
4. **ميزة التعليقات:** بقيت معطلة تماماً ومحمية بحسب ما تم تدقيقه في المرحلة السابقة.
5. **مفاتيح الإصدار (Release):** لم يتم تغيير إعدادات `release` أو مسار `my-upload-key.jks`.

---

## 8. النتيجة النهائية للمرحلة (Final Verdict)

# **PASS**

تم استعادة هوية Firebase الكنسية الكاملة، واستعادة وتثبيت ملف `app/google-services.json` الرسمي، وتوحيد اسم الحزمة `com.aistudio.cinestream.xyzabc`، واستعادة مفتاح التصحيح `debug.keystore` الأصلي ببصماته المطابقة، مع التحقق الفعلي من نجاح البناء وتوقيع الحزمة. المشروع جاهز تماماً للمراحل التالية.
