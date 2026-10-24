# PHASE 22.25 — إصلاح نتائج جولة التحقق وإعادة تثبيت بصمات Debug

**التاريخ:** 21 أكتوبر 2026  
**مصادر العمل:** `CINESTREAM_PRO.md` و`CINESTREAM_ADMIN.md` المقدمان من المستخدم، مع أرشيفي `CINESTREAM-PRO-IDENTITY-LOCKED.zip` و`CINESTREAM-ADMIN-IDENTITY-LOCKED.zip`.  
**نطاق الإصلاح:** ملفات محلية فقط. لا اتصال ببيانات Firebase الإنتاجية، ولا نشر لقواعد Firestore، ولا تغيير في هوية Firebase المعتمدة.

## 1. نتيجة مراجعة التقريرين

### User App
- كانت هوية الحزمة وFirebase App ID وملف Google Services متطابقة.
- فشل `test:identity` بسبب وجود `debug.keystore` مولّد حديثًا ببصمة SHA-1 مختلفة.
- فشلت مهام الاختبار والتجميع وLint عند `validateWebClientId` لأن بيئة التشغيل مررت `WEB_CLIENT_ID=web_client_mock_id` رغم وجود عميل ويب صحيح في ملف Firebase.
- أشار التقرير أيضًا إلى خطر إنشاء BuildConfig غير صالح نحويًا عند وجود متغيرات `.env` فارغة.

### Admin Dashboard
- كانت هوية الحزمة وFirebase App ID وعميل Web OAuth صحيحة.
- كانت بصمة `debug.keystore` المحلي مختلفة، رغم وجود المفتاح الصحيح داخل سير CI.
- نجح `processDebugGoogleServices` و`assembleDebug` و`lintDebug` في جولة AI Studio السابقة؛ فشلت 2 من 42 اختبارات وحدة لأنهما توقعا أن تكون التعليقات متاحة رغم قرار إلغائها.
- لا يوجد دليل من التقارير على نجاح تجربة Google Sign-In التفاعلية على جهاز؛ لم يكن هناك محاكي أو جهاز متصل.

## 2. الإصلاحات المنفذة

1. استُعيد `debug.keystore` لكل تطبيق من بياناته المضمّنة في `.github/workflows/build.yml`، بدل توليد مفتاح جديد أو استخدام مفتاح التطبيق الآخر.
2. تحقق `keytool` من المطابقة:
   - User App SHA-1: `1e0f4a4a0ee84b7023e59504b3a978382d019e2a`
   - User App SHA-256: `c3aed0b9552cbb730057309495a379e1e8cc73eefabc4deacc39616fc12d27d3`
   - Admin SHA-1: `54a814fb04debd983f336ef82e87820edb760f12`
   - Admin SHA-256: `1f32aed31251da8aafb8bc5fe9fb07c491d798771ca0a347e34e2147100e4164`
3. عُدّل `app/build.gradle.kts` في User App لاستخراج Web OAuth Client ID من `app/google-services.json` للعميل Android ذي الحزمة المطابقة، بدل الاعتماد على متغير بيئة قد يحتوي قيمة placeholder.
4. تتجاهل تهيئة User App قيمة `WEB_CLIENT_ID` المعروفة بأنها placeholder وتستعمل المعرّف الرسمي الموجود في JSON. أي معرّف غير فارغ وغير placeholder لكنه مختلف عن المعرف الرسمي يظل فشلًا مقصودًا.
5. .يطبع تطبيع `.env` القيم المفقودة أو الفارغة من `.env.example` كي لا يُولّد Secrets Gradle Plugin حقول BuildConfig بلا قيمة؛ ولا يتم تغيير القيم السرية الحقيقية غير الفارغة. لا يُضمّن ملف `.env` خاص بالمطور في التسليم.
6. تغيّرت قيمة TMDB الاحتياطية في `.env.example` إلى `YOUR_TMDB_API_KEY`، وأصبح كود TMDB يعامل قيم placeholder على أنها غير مهيأة بدل إرسالها كأنها مفتاح صالح.
7. أُضيف فحص لقيم Cloudinary الاحتياطية المعروفة قبل تهيئة الخدمة أو رفع الصورة، دون الكتابة فوق أي بيانات حقيقية غير فارغة.
8. صُححت توقعات وحدات الإدارة لكي يبقى `isCommentAllowed == false` دائمًا؛ كما أن تغيير الحقل القديم `canComment` وحده لا يُحسب قيدًا فعّالًا على المستخدم.
9. أضيفت اختبارات رجعية تتحقق من منطق استخراج Web OAuth ومن توقعات التعليقات المتوافقة مع قرار الإلغاء.

## 3. الاختبارات التي نجحت داخل بيئة الإصلاح

| المشروع | الاختبار | النتيجة |
|---|---|---:|
| User App | `npm run test:identity` | 5/5 PASS |
| User App | `npm run test:rules` | 8/8 PASS |
| Admin | `npm run test:identity` | 6/6 PASS |
| Admin | `npm run test:static` | 8/8 PASS |
| **المجموع** | **اختبارات Node.js الساكنة** | **27/27 PASS** |

كذلك استُخرجت بصمات SHA-1 وSHA-256 من المفتاحين المستعادين باستخدام `keytool`، وطابقت قيم قفل الهوية.

## 4. الاختبارات التي لم يمكن إكمالها في هذه البيئة

تمت محاولة الأمر `WEB_CLIENT_ID=web_client_mock_id ./gradlew validateWebClientId --no-daemon` في كلا المشروعين. توقف Gradle Wrapper قبل بدء Gradle لأنه لم يتمكن من حل `services.gradle.org` (`UnknownHostException`). لذلك لم تُنفذ مهام البناء التالية في هذه البيئة:

- `:app:processDebugGoogleServices`
- `signingReport`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:lintDebug`
- اختبار Firebase Rules Emulator
- تسجيل Google Sign-In التفاعلي على جهاز فعلي أو محاكي

يجب إعادة تشغيل المهام المذكورة داخل Google AI Studio حيث تتوفر بيئة Android/Gradle. لا يصف هذا التقرير أيًا من تلك الاختبارات بأنها ناجحة في الجولة الحالية.

## 5. ضوابط لم تتغير

- معرفا الحزمة الكنسيان كما في Firebase: `com.aistudio.cinestream.xyzabc` و`com.aistudio.cinestreamadmin.adcxz`.
- مشروع Firebase: `cinestream-sulo`، رقم المشروع `979447256418`.
- لم تتغير قواعد `firestore.rules` النشطة أو ملف `firebase.json` في أي من المشروعين.
- لم تُنشر قواعد Firestore ولم تتغير أي بيانات إنتاجية.
- التعليقات ما زالت ملغاة؛ لا تسمح بكتابة `/comments`.
- الاقتصاد المباشر من العميل ما زال محظورًا؛ لا تفعيل لواجهة خلفية أو هجرة إلى Cloudflare.
- بقي توقيع Release منفصلًا وغير مثبت؛ قفل SHA هنا لشهادة Debug فقط.

## 6. الحكم النهائي

**الحالة:** `STATIC CHECKS PASS — ANDROID BUILD/UNIT TESTS BLOCKED BY GRADLE NETWORK ACCESS`.

عولج سببا فشل التحقق من الهوية في الملفات المحلية، وصُححت اختبارات التعليقات القديمة، وأصلح مسار حل Web OAuth في User App. مع ذلك، يلزم تشغيل Gradle واختبارات الوحدة في Google AI Studio قبل اعتبار الإصلاح معتمدًا، ويلزم اختبار Google Sign-In على جهاز فعلي أو محاكي قبل تأكيد نجاحه التشغيلي.
