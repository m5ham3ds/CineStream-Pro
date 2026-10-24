# PHASE 22.24 — تقرير تثبيت هوية Firebase وإصلاح Google Sign-In

**التاريخ:** 20 أكتوبر 2026  
**النطاق:** CineStream User App وCineStream Admin Dashboard — نسختان مستقلتان  
**حالة الهوية المحلية:** `LOCKED / MATCHED TO USER-SUPPLIED FIREBASE JSON`  
**حالة البناء والتشغيل:** `BLOCKED — Gradle distribution download unavailable in this environment`  
**النشر أو الوصول إلى الإنتاج:** لم يحدث.

## 1. مصدر الحقيقة المعتمد

تم استخدام ملف `google-services.json` الذي أرفقه المستخدم دون إنشاء ملف بديل يدويًا. الملف يحدد Firebase Project ID `cinestream-sulo` ورقم المشروع `979447256418` ومخزن `cinestream-sulo.firebasestorage.app`.

SHA-256 للملف الرسمي كما نُسخ إلى كلا المشروعين:

`9bdb880b9e47424f8ce833de7631967ff3b37c84795b6d3075e7faa8e390d895`

## 2. هوية Android وOAuth المقفلة

| القيمة | CineStream User App | CineStream Admin Dashboard |
|---|---|---|
| `applicationId` | `com.aistudio.cinestream.xyzabc` | `com.aistudio.cinestreamadmin.adcxz` |
| Firebase Android App ID | `1:979447256418:android:037a9b3a1393e550a9883b` | `1:979447256418:android:ea5b570266ae1aa6a9883b` |
| Android OAuth Client ID | `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com` | `979447256418-m7u3batvou0mmrubfkmsnmunvpedo0dd.apps.googleusercontent.com` |
| Debug SHA-1 المسجل | `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A` | `54:A8:14:FB:04:DE:BD:98:3F:33:6E:F8:2E:87:82:0E:DB:76:0F:12` |
| Debug SHA-256 | `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE:FA:BC:4D:EA:CC:39:61:6F:C1:2D:27:D3` | `1F:32:AE:D3:12:51:DA:8A:AF:B8:BC:5F:E9:FB:07:C4:91:D7:98:77:1C:A0:A3:47:E3:4E:21:47:10:0E:41:64` |

كلا التطبيقين يستخدم Web OAuth Client ID المشترك الوارد في الملف الرسمي. بقي `namespace = "com.example"` كما هو؛ لم تتم إعادة تسمية حزم Kotlin أو مراجع `R`.

## 3. الملفات التي تم تعديلها

1. في المشروعين: `app/build.gradle.kts` — إعادة `applicationId` من قيمة AI Studio المؤقتة إلى الحزمة المسجلة في Firebase.
2. في المشروعين: `app/google-services.json` — تثبيت النسخة التي رفعها المستخدم حرفيًا.
3. في المشروعين: `debug.keystore` — استعادة مفتاح Debug الصحيح الخاص بكل مشروع من حمولة `.github/workflows/build.yml` الأصلية، كلٌّ على حدة.
4. في تطبيق المستخدم: `.github/workflows/build.yml` — تحديث حمولة JSON المضمنة لتكون مطابقة بايت-لبايت للملف الرسمي، بما في ذلك `firebase_url`، حتى لا يستبدل CI النسخة الصحيحة بنسخة قديمة ناقصة.
5. في لوحة الإدارة: إزالة تعريف `default_web_client_id` اليدوي المكرر من `strings.xml` ليأتي المورد من Google Services Plugin اعتمادًا على JSON الرسمي.
6. في لوحة الإدارة: `LoginViewModel.kt` — تصفير `isGoogleLoading` و`isLoading` في الفشل، والاستثناء، وغياب المستخدم، ورفض صلاحية الإدارة، والنجاح؛ والتحقق من أن ID token ليس فارغًا وإظهار خطأ أوضح.
7. في لوحة الإدارة: `LoginScreen.kt` — إيقاف الاختيار التلقائي للحساب حتى يستطيع المسؤول اختيار الحساب الذي يملك صلاحية الوصول.
8. أضيف `CINESTREAM_FIREBASE_IDENTITY_LOCK.json` في كل مشروع، مع اختبارات `npm run test:identity` لحراسة الحزم ومعرفات Firebase وبصمات المفاتيح وعميل الويب. التقرير الحالي يحل محل ملاحظة الهوية المؤقتة في تقرير PHASE 22.23؛ التقارير الأقدم تبقى سجلًا تاريخيًا.

## 4. نتائج الاختبارات المنفذة هنا

| الاختبار | User App | Admin Dashboard |
|---|---:|---:|
| اختبارات حواجز قواعد Firestore الساكنة | 8/8 PASS | 8/8 PASS |
| اختبارات قفل الهوية الجديدة | 4/4 PASS | 5/5 PASS |
| تحقق `keytool` من SHA-1 وSHA-256 مقابل OAuth | PASS | PASS |
| JSON/XML والتطابق بين `applicationId` وعميل JSON | PASS | PASS |
| تطابق حمولة JSON في CI بايت-لبايت | PASS | لا توجد حمولة JSON مولدة في سير الإدارة؛ يعتمد على الملف الموجود في المستودع |

الأوامر التي نجحت:
- User App: `npm run test:rules` ثم `npm run test:identity` من جذر المشروع.
- Admin: `npm run test:static` ثم `npm run test:identity` من `firestore-tests/`.

## 5. ما تعذر اختباره

جرّبت `bash ./gradlew --version --no-daemon` بعد تثبيت الهوية في كل مشروع. لم تكن توزيعة Gradle 9.3.1 موجودة محليًا، وتعذر تنزيلها من `services.gradle.org`؛ أظهرت محاولة الإدارة `java.net.UnknownHostException: services.gradle.org` وتعذر التنزيل في محاولة تطبيق المستخدم أيضًا. لذلك لم يبدأ Gradle فعليًا، ولم يتم بناء APK، ولم يتم تنفيذ `processDebugGoogleServices` أو اختبارات Kotlin/Robolectric أو Lint.

لم يتصل أي اختبار بـFirebase الحي، ولم يتم تغيير مزود Google في Authentication أو وثائق `/admins` أو قواعد Firestore أو بيانات المستخدمين. ولم يتم تفعيل Comments أو Cloudflare.

## 6. تفسير فشل Google Sign-In في لوحة الإدارة

وجدنا انحرافًا مؤكدًا في النسخة السابقة: `applicationId` الفعلي لم يكن يطابق الحزمة المسجلة في ملف Firebase/OAuth، ولم يكن ملف JSON الرسمي مثبتًا في `app/`. هذه مشكلة كافية لإفساد مطابقة OAuth/Google Services. أصلحناها وأثبتنا أن بصمة مفتاح Debug تطابق `certificate_hash` الخاص بالحزمة الكنسية.

وجدنا أيضًا عيبًا برمجيًا كان يُبقي مؤشر تحميل Google مفعّلًا في بعض مسارات الفشل أو الرفض، ثم أصلحناه. لكن لا يمكن إعلان أن تسجيل الدخول نجح وقت التشغيل قبل البناء على بيئة متصلة وتجربته فعليًا. وحتى عندما تتم مصادقة Google بنجاح، فإن حسابًا ليس مالك المشروع المعتمد أو ليس لديه `/admins/{uid}.enabled == true` سيُرفض من لوحة الإدارة وفق سياسة التفويض الحالية. هذا رفض صلاحيات متوقع وليس خطأ في Google Sign-In.

## 7. حدود قفل الهوية

قفل الهوية يغطي ملف Firebase الرسمي ومفاتيح Debug المستعادة. لا يقفل بصمة إصدار Release: إعدادات الإصدار تعتمد على keystore ومتغيرات بيئة مستقلة (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`). يجب استخراج SHA-1/SHA-256 من مفتاح Release الفعلي وتسجيلهما في Firebase Console قبل إصدار نسخة Release. لم يتم تغيير أي إعداد داخل Firebase Console.

**الحالة النهائية:** قفل الهوية المحلي ومراجعاته الساكنة ناجحة؛ البناء واختبار Google Sign-In على الجهاز ما زالا `BLOCKED / NOT RUN` حتى تتوفر بيئة Gradle متصلة وهاتف/بيئة اختبار.
