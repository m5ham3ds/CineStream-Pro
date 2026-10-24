# تقرير تدقيق ومطابقة عقود تطبيق المستخدم — CINESTREAM USER APP
## PHASE 22.14 — USER APP CONTRACT RECONCILIATION REPORT

**تاريخ التدقيق:** 19 أكتوبر 2026  
**نطاق التدقيق:** تطبيق CineStream User App (Android / Kotlin / Compose)  
**طبيعة المرحلة:** تدقيق وتوثيق مستقل فقط — بدون أي تعديلات برمجية أو تغييرات في الإعدادات  
**المرجع الخارجي للمقارنة:** قائمة التحقق والعقد المشترك لتقرير Admin (`PHASE_22.14_ADMIN_CONTRACT_RECONCILIATION_REPORT.md`)

---

## 1. الملخص التنفيذي (Executive Summary)

تم تنفيذ تدقيق شامل ومستقل لكافة جوانب العقود المشتركة بين تطبيق المستخدم **CineStream User App** ولوحة التحكم **CineStream Admin Dashboard** بناءً على الملفات البرمجية والتهيئة الفعلية الموجودة في المستودع الحالي.

### أهم نتائج التدقيق:
1. **نموذج البلاغات (`/reports`):** متوافق (`MATCH`). ينشئ التطبيق البلاغ بحالة `"PENDING"` بأحرف كبيرة حصرًا، ويستثني حقول الإدارة (`resolvedBy`, `resolvedAt`, `resolutionNotes`) عند الإنشاء، مع إمكانية قراءة ملاحظات الحل (`resolutionNotes`) عند قيام الإدارة بمعالجة البلاغ، ودعم كامل لكافة الحالات (`RESOLVED`, `DISMISSED`) وتطبيع البدائل مثل (`REJECTED`).
2. **طلبات الاشتراك وإلغائها (`/pro_requests`):** متوافق (`MATCH`). يرسل التطبيق حقول الإنشاء القياسية، ويتعامل مع الحالات الأربع (`PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`). تنفذ عملية الإلغاء كتابة دقيقة ومقتصرة على الحقول الثلاثة المصرح بها فقط (`status = "CANCELLED"`, `cancelledAt`, `updatedAt`) بما يتوافق حرفياً مع قيود `firestore.rules`. الترقية الفعلية للمستخدم تتم سحابياً عبر مستند `/users/{uid}` دون تفعيل ذاتي من العميل.
3. **مسارات Firestore والصلاحيات:** متوافق محلياً (`MATCH`) / غير متحقق منه على السحابة الحية (`UNVERIFIED`). مسارات التطبيق الـ 22 تلتزم بقيود الأمان في `firestore.rules` المحلي، حيث تُمنع كتابة الحقول الحساسة (الرتب، الحظر، المحفظة، بيانات الاشتراك) من قِبل المستخدم.
4. **محرك البحث ومصادر الدبلجة:** متوافق (`MATCH`). يقرأ التطبيق `/config/search_order` ويدعم المفاتيح المتعددة (`arabicDubbed`, `arabicDub`, `arabic_dub`)، وحالات المجموعات (`ACTIVE`, `MAINTENANCE`, `DISABLED`). يفصل محرك الترتيب `DubbedSearchRankingEngine` و `SearchTriggerParser` و `MediaDisplayTitleResolver` بين العمل المطلوب والمحفزات، ويعتمد TMDB كهوية رسمية مع تفضيل عنوان المصدر فقط كعنوان عرض للنسخة المدبلجة المؤكدة، مع منع مدبلج الأنمي برمجياً.
5. **هوية Firebase والحزم:** غير متطابق (`MISMATCH`). يوجد اختلاف مثبت بين القيم المرجعية الواردة في متطلبات التدقيق السابقة (`cinestream-sulo`, `979447256418`, `com.aistudio.cinestream.xyzabc`) وبين القيم الحالية في ملفات تكوين بيئة التطوير (`ai-studio-applet-webapp-bbb09`, `938184974903`, `com.aistudio.cinestream.bdbnfg`) مع غياب ملف `app/google-services.json` المحلي.
6. **وضع التعليقات (`/comments`):** متوافق ومؤجل (`MATCH`). الميزة معطلة تماماً؛ لا توجد أي واجهات مستخدم، ولا دوال قراءة أو كتابة على `/comments`، وقواعد `firestore.rules` تحظر أي وصول عبر قاعدة المنع الشاملة.
7. **النتيجة الإجمالية للتدقيق:** **PASS WITH LIMITATIONS** (اجتياز مع قيود مسجلة بخصوص هوية Firebase السحابية والتثبت من القواعد الحية).

---

## 2. الملفات التي فُحصت (Examined Files)

تم فحص ومراجعة الملفات البرمجية التالية بدقة دون إجراء أي تعديل:

### ملفات النماذج والبيانات:
- `app/src/main/java/com/example/data/model/Report.kt`
- `app/src/main/java/com/example/data/model/SubscriptionModels.kt`
- `app/src/main/java/com/example/data/model/EconomyModels.kt`
- `app/src/main/java/com/example/data/model/UserRestrictions.kt`
- `app/src/main/java/com/example/domain/models/VariantModels.kt`
- `app/src/main/java/com/example/domain/models/Media.kt`

### ملفات المستودعات والخدمات:
- `app/src/main/java/com/example/data/repository/ReportRepository.kt`
- `app/src/main/java/com/example/data/repository/PointsRepository.kt`
- `app/src/main/java/com/example/data/repository/AuthRepository.kt`
- `app/src/main/java/com/example/data/repository/UserSecurityManager.kt`
- `app/src/main/java/com/example/data/repository/EconomyConfigRepository.kt`
- `app/src/main/java/com/example/data/repository/TemporaryFirebaseEconomyRepository.kt`

### ملفات البحث والدبلجة وترتيب المصادر:
- `app/src/main/java/com/example/domain/search/SearchTriggerParser.kt`
- `app/src/main/java/com/example/domain/search/DubbedSearchRankingEngine.kt`
- `app/src/main/java/com/example/domain/search/MediaDisplayTitleResolver.kt`
- `app/src/main/java/com/example/domain/search/MediaTitleMatcher.kt`
- `app/src/main/java/com/example/extension/managed/searchorder/SearchOrder.kt`
- `app/src/main/java/com/example/extension/managed/searchorder/SearchOrderDataSource.kt`
- `app/src/main/java/com/example/extension/managed/repository/ManagedExtensionRealtimeSyncManager.kt`
- `app/src/main/java/com/example/ui/screens/search/SearchViewModel.kt`

### ملفات واجهات المستخدم والتحقق من التعليقات:
- `app/src/main/java/com/example/ui/screens/profile/SubscriptionScreen.kt`
- `app/src/main/java/com/example/ui/screens/profile/HelpSupportScreen.kt`
- `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`
- كامل حزمة `app/src/main/java/com/example/ui/` للبحث عن شاشات أو عناصر للتعليقات.

### ملفات التكوين وقواعد الأمان والهوية:
- `firestore.rules`
- `firebase-applet-config.json`
- `firebase.json`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- التحقق من وجود `app/google-services.json` عبر شجرة المشروع.

---

## 3. مصفوفة المطابقة للمجالات الستة (Reconciliation Matrix)

| المجال | دليل User App | مقارنة دليل Admin (المرجع) | النتيجة | النقص أو الخطر |
| :--- | :--- | :--- | :--- | :--- |
| **A. نموذج البلاغات `/reports`** | ينشئ بحالة `"PENDING"`، يستثني حقول الإدارة، يقرأ الحالات (`RESOLVED`, `DISMISSED`) و `resolutionNotes`. يمنع التعديل والحذف. | يدير الحالات الثلاث (`PENDING`, `RESOLVED`, `DISMISSED`)، ويكتب `resolutionNotes`, `resolvedBy`, `resolvedAt`. | **MATCH** | لا يوجد خطر؛ التطبيق يدعم أيضاً تطبيع الحالات البديلة (`REJECTED`) والأحرف الصغيرة دون انهيار. |
| **B. طلبات Pro والإلغاء `/pro_requests`** | يرسل بيانات الإنشاء كاملة بحالة `"PENDING"`. يلغي الطلب بحصر التحديث في `['status', 'updatedAt', 'cancelledAt']` بحالة `"CANCELLED"`. يدعم باقات `pro` و `pro_lite`. | يعتمد الحالات الأربع (`PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`) ويشترط حقول `status` و `cancelledAt` و `updatedAt` للإلغاء. | **MATCH** | متطابق تماماً مع قيود الأمان المطبقة في سطر 545 من `firestore.rules`. |
| **C. مسارات Firestore والصلاحيات** | يستخدم 22 مساراً تشغيلياً. يمنع تعديل الرتب والاشتراكات والنقاط والحظر ذاتياً. الصلاحيات مقيدة بمطابقة `firestore.rules`. | يتوقع قفل الإعدادات والمهام وحماية ملف المستخدم من التعديلات الحساسة. | **MATCH** (محلياً) / **UNVERIFIED** (سحابياً) | لا يمكن التحقق من مطابقة القواعد الحية في الإنتاج السحابي إلا بعد عملية نشر معتمدة. |
| **D. البحث ومصادر الدبلجة** | يدعم مفاتيح `arabicDubbed`, `arabicDub`, `arabic_dub`. يفصل بين العمل المطلوب ومحفزات الدبلجة. TMDB هو المرجع الرسمي مع حظر دبلجة الأنمي. | ينظم الإضافات ويوفر أولويات البحث وتفعيل/تعطيل المجموعات في `/config/search_order`. | **MATCH** | التطبيق محمي بفضل دعم المفاتيح البديلة المتعددة وحالات المجموعات التشغيلية. |
| **E. هوية Firebase والحزم** | لا يوجد `google-services.json` محلياً. المشروع في الإعدادات: `ai-studio-applet-webapp-bbb09`. المعرف: `938184974903`. الحزمة: `com.aistudio.cinestream.bdbnfg`. | القيم المرجعية: المشروع `cinestream-sulo`، المعرف `979447256418`، الحزمة `com.aistudio.cinestream.xyzabc`. | **MISMATCH** | عدم تطابق بيانات المشروع والحزمة بين بيئة التطوير الحالية والقيم المرجعية السابقة. |
| **F. وضع التعليقات** | ميزة التعليقات معطلة برمجياً؛ لا توجد أي واجهة أو استدعاء شبكي. قواعد Firestore تحظر المسار كلياً. | ميزة التعليقات مؤجلة ومحظورة. | **MATCH** | لا يوجد خطر؛ الميزة مؤمنة ومغلقة تماماً. |

---

## 4. الأدلة الفعلية من User App (Concrete Evidence)

### A. تفاصيل نموذج البلاغات (`/reports/{reportId}`)
1. **المسار:** `reports/{reportId}`
2. **أسماء الحقول وأنواع البيانات عند الإنشاء (`toCreationFirestoreMap` في `Report.kt`):**
   - `id`: `String` (معرف المستند)
   - `reportId`: `String` (مطابق لـ `id`)
   - `userId`: `String` (معرف المستخدم الحالي من Firebase Auth)
   - `userEmail`: `String` (بريد المستخدم)
   - `type`: `String` (يتم تحويله إلى أحد القيم المعيارية: `"STREAM"`, `"BUG"`, `"ISSUE"`, `"CONTENT"`)
   - `targetType`: `String` (يتم تحويله إلى: `"USER"`, `"STREAM"`, `"EXTENSION"`, `"MEDIA"`)
   - `targetId`: `String` (معرف الوسيط أو المستخدم موضوع البلاغ)
   - `title`: `String`
   - `description`: `String`
   - `status`: `String` = `"PENDING"` (أحرف كبيرة دائمًا عند الإنشاء)
   - `createdAt`: `Long` (بالملي ثانية)
   - حقول التوافق القديمة: `reason` (تطابق `title`), `details` (تطابق `description`), `contentId` (تطابق `targetId`), `contentType`.
3. **الحقول المستبعدة إجبارياً عند الإنشاء من العميل:**
   - الدالة `toCreationFirestoreMap()` تحذف صراحة: `resolvedAt`, `resolvedBy`, `resolutionNotes`.
4. **سلوك قراءة البلاغ واستخراج النتيجة (`fromDocument` في `Report.kt`):**
   - يقرأ `status` ويقوم بتطبيعها (`trim().uppercase()`):
     * `"RESOLVED"` -> `"RESOLVED"`
     * `"DISMISSED"`, `"REJECTED"` -> `"DISMISSED"`
     * `"PENDING"` -> `"PENDING"`
     * يدعم أيضاً الأحرف الصغيرة مثل `"resolved"` أو `"pending"` أو `"rejected"` بتحويلها تلقائياً للأحرف الكبيرة.
   - يقرأ `resolutionNotes`: `doc.getString("resolutionNotes") ?: ""`
   - يقرأ `resolvedAt` (يدعم `Timestamp` و `Number`) و `resolvedBy`.
   - **النتيجة:** المستخدم يستطيع قراءة `resolutionNotes` ومعرفة قرار الإدارة بالكامل من خلال استدعاء `ReportRepository.getUserReports()`.
5. **الصلاحيات في `firestore.rules`:**
   - القراءة: مسموحة لمالك البلاغ (`resource.data.userId == request.auth.uid`) أو المسؤول (`isAdmin()`).
   - الإنشاء: مسموح فقط بحالة `PENDING` أو `pending`، وبشرط عدم تضمين `resolvedBy` أو `resolvedAt` أو `resolutionNotes`.
   - التعديل والحذف: محظور على المستخدم العادي ومحصور بـ `isAdmin()`.

---

### B. تفاصيل طلبات Pro والإلغاء (`/pro_requests/{requestId}`)
1. **المسار:** `pro_requests/{requestId}`
2. **الحقول المرسلة عند تقديم الطلب (`submitProRequest` في `PointsRepository.kt`):**
   - `requestId`: `String` (بصيغة `req_{timestamp}_{uid.take(6)}`)
   - `userId`: `String` (معرف المستخدم `auth.currentUser.uid`)
   - `userEmail`: `String`
   - `userName`: `String`
   - `planId`: `String` (مثل `"pro_30d"`, `"pro_lite_7d"`)
   - `requestedPlan`: `String` (`"pro"` أو `"pro_lite"`)
   - `requestedDuration`: `String` (مثل `"7 days"`, `"1 month"`)
   - `durationDays`: `Long` (القيم المدعومة: 1, 7, 10, 30)
   - `status`: `String` = `"PENDING"`
   - `paymentReference`: `String`
   - `notes` و `userNote`: `String`
   - `createdAt`: `Long` (التوقيت الحالي)
   - `updatedAt`: `Long` (التوقيت الحالي)
   - اختياري: `paymentMethod`, `paymentProofUrl`
3. **عملية الإلغاء من طرف المستخدم (`cancelProRequest` في `PointsRepository.kt`):**
   - يتحقق التطبيق أولاً من أن المستخدم الحالي هو صاحب الطلب (`requestOwnerId == currentUser.uid`).
   - يتحقق من أن الحالة الحالية هي `"PENDING"` (لا يمكن إلغاء الطلبات المعتمدة أو المرفوضة).
   - ينفذ عملية التحديث الحصرية:
     ```kotlin
     reqRef.update(
         mapOf(
             "status" to "CANCELLED",
             "cancelledAt" to now,
             "updatedAt" to now
         )
     ).await()
     ```
   - هذا التحديث يطابق حرفياً شرط الأمان في `firestore.rules`:
     `request.resource.data.diff(resource.data).affectedKeys().hasOnly(['status', 'updatedAt', 'cancelledAt'])`
4. **الباقات ورتب الاشتراك المدعومة (`SubscriptionModels.kt` و `EconomyModels.kt`):**
   - الرتب المعيارية: `FREE`, `PRO_LITE`, `PRO` (مع تطبيع الرتب القديمة `PREMIUM` و `VIP` إلى `PRO`).
   - معرفات الخطط (Plan IDs):
     * `free`: مجانية (0 يوم).
     * `pro_lite_1d`: باقة لايت يوم واحد (50 نقطة).
     * `pro_lite_7d`: باقة لايت 7 أيام (250 نقطة).
     * `pro_lite_10d`: باقة لايت 10 أيام (350 نقطة).
     * `pro_30d`: باقة برو الكاملة 30 يوم (1000 نقطة).
5. **كيفية تعامل التطبيق مع تحديثات الإدارة:**
   - تطبيق المستخدم **لا يقوم أبداً بتفعيل الاشتراك ذاتياً**.
   - عند موافقة Admin، يحدّث Admin مستند المستخدم `/users/{uid}` بالحقول: `subscriptionTier`, `subscriptionStatus = "ACTIVE"`, `subscriptionExpiresAt`, `subscriptionStartedAt`.
   - يستمع التطبيق لتحديثات مستند المستخدم عبر `AuthRepository` و `CloudSyncManager` ويحدّث حالة العرض وحجب الإعلانات (`isAdFree = true`).
   - قاعدة الاشتراك الثابتة: الاشتراك يمنح فقط إزالة الإعلانات (`REMOVE_ADS ONLY`)، ولا يتدخل أو يحدد جودة التشغيل أو التحميل.

---

### C. مسارات Firestore المشتركة والصلاحيات
تم حصر المسارات المشتركة الـ 22 التي يستخدمها التطبيق وعلاقتها بقواعد الأمان:

1. `/users/{userId}`: قراءة وتحديث من المالك. يُمنع المستخدم من تعديل حقول الرتب، الاشتراك، النقاط، الحظر، وصلاحيات الميزات.
2. `/users/{userId}/fcmTokens/{tokenId}`: كتابة وحذف رموز الإشعارات الخاصة بالجهاز.
3. `/users/{userId}/library/{itemId}`: مزامنة قائمة المفضلات والمكتبة الخاصة بالمستخدم.
4. `/users/{userId}/history/{itemId}`: مزامنة سجل المشاهدة ومواقع التوقف.
5. `/users/{userId}/watched_episodes/{episodeId}`: تتبع الحلقات التي تمت مشاهدتها.
6. `/users/{userId}/settings/notifications`: تفضيلات الإشعارات الفردية.
7. `/users/{userId}/point_transactions/{txId}`: دفتر الأستاذ لحركات النقاط (قراءة فقط للمستخدم، الكتابة للإدارة أو المحرك الموثوق).
8. `/users/{userId}/task_claims/{taskId}`: تسجيل إتمام المهام (كتابة لمرة واحدة بحالة PENDING/مكتمل، والحذف محظور).
9. `/public_profiles/{userId}`: الملف العام للمستخدم (قراءة عامة إذا كان `isProfilePublic == true`، وكتابة للمالك بحقول مسموحة فقط).
10. `/conversations/{convId}`: محادثات P2P (قراءة وكتابة للأطراف المشتركة `participants`، ومحظورة على المحظورين من الدردشة).
11. `/conversations/{convId}/messages/{msgId}`: رسائل الدردشة، مع السماح للمستلم بإضافة التفاعلات (`reactions`) وإخفاء الرسالة لنفسه (`deletedFor`).
12. `/support_conversations/{userId}`: تذاكر الدعم الفني الخاصة بالمستخدم.
13. `/support_conversations/{userId}/messages/{msgId}`: رسائل الدعم (يُمنع المستخدم من تزوير رتبة `admin`).
14. `/reports/{reportId}`: إنشاء بلاغات المحتوى والأعطال بحالة `PENDING` وقراءتها من صاحب البلاغ.
15. `/pro_requests/{requestId}`: إنشاء طلبات الترقية وإلغاء المعلق منها (`CANCELLED`).
16. `/reward_tasks/{taskId}`: قراءة المهام النشطة المتاحة لربح النقاط (الكتابة محصورة بـ Admin).
17. `/leaderboard/{period}`: قراءة لوحة المتصدرين الأسبوعية والعامة (الكتابة محصورة بـ Admin).
18. `/stories/{storyId}`: قصص المستخدمين المؤقتة.
19. `/config/app`: قراءة إعدادات التشغيل (الصيانة، التحديث الإجباري).
20. `/config/economy`: قراءة قواعد ومكافآت وتكاليف النقاط.
21. `/config/features`: قراءة قواطع ومفاتيح الميزات (`FeatureState`).
22. `/config/search_order`: قراءة الترتيب المعتمد للبحث والإضافات ومجموعات الدبلجة.
23. `/managed_extensions/{extensionId}`: قراءة الإضافات البرمجية النشطة المعتمدة من الإدارة.

**القيود وقواعد Firestore:**
- كافة القواعد البرمجية الصارمة مطبقة محلياً في `firestore.rules` وتغلق أي مسار غير مصرح به عبر قاعدة الحظر النهائي (`match /{document=**} { allow read, write: if false; }`).

---

### D. تفاصيل البحث ومصادر الدبلجة
1. **قراءة تكوين الترتيب (`SearchOrderDataSource.kt`):**
   - يقرأ التطبيق مستند `/config/search_order` بمصدر بيانات للقراءة فقط.
   - يدعم استخراج القوائم مع بدائل التوافق:
     * المحتوى المدبلج للعربية: يقرأ `arabicDubbed`؛ وإذا لم توجد يفحص `arabicDub`، ثم `arabic_dub`.
     * المحتوى المدبلج للإنجليزية: يقرأ `englishDubbed`؛ وإذا لم توجد يفحص `englishDub`، ثم `english_dub`.
     * الأفلام: `movie`.
     * المسلسلات: `tv` مع بديل `series`.
     * الأنمي: `anime`.
   - يدعم استخراج حالة المجموعات من الخريطة المضمنة `dubbingConfig` أو الحقول الجذرية:
     * `arabicDubbedStatus`: الافتراضي `ACTIVE` إذا لم تُحدد.
     * `englishDubbedStatus`: الافتراضي `MAINTENANCE` إذا لم تُحدد.
2. **تحليل محفزات البحث (`SearchTriggerParser.kt`):**
   - يلتقط الكلمات المفتاحية الدالة على الدبلجة: `مدبلج`, `مدبلجة`, `مدبلجين`, `مدبلجون`, `دبلجة`, والإنجليزية `dubbed`.
   - يطبّق تنظيف علامات التشكيل العربية (Tashkeel) وحدود الكلمات المستقلة لتجنب الالتقاط الخاطئ للكلمات الجزئية.
   - يستخرج اسم العمل النظيف (`cleanQuery`) بالكامل لتوجيهه إلى قاعدة بيانات TMDB، مع تحديد ما إذا كان البحث مجرد كلمة محفزة عامة أو يتضمن اسم عمل محدد.
3. **أولوية ترتيب النتائج (`DubbedSearchRankingEngine.kt`):**
   - **المستوى الأول (Tier 1):** العمل المطلوب المطابق لاسم البحث ومؤكد توفر مصدر مدبلج له (`hasConfirmedDubbedSource == true`).
   - **المستوى الثاني (Tier 2):** العمل المطلوب المطابق لاسم البحث ولكنه متوفر كمصدر مترجم فقط (لا يُحذف العمل المطلوب أبداً من النتائج).
   - **المستوى الثالث (Tier 3):** اقتراحات بديلة مدبلجة تشترك اسمياً وتطابقياً مع استعلام المستخدم بنسبة تشابه تتجاوز العتبة الأدنى (`MIN_RELEVANCE_THRESHOLD = 0.35`).
   - استبعاد حاسم: يمنع التطبيق منعاً باتاً ظهور أي أعمال مدبلجة عشوائية أو غير ذات صلة لا تشترك في أي تطابق مع استعلام المستخدم.
4. **مرجعية العناوين وتكامل TMDB (`MediaDisplayTitleResolver.kt`):**
   - TMDB هو الهوية والكتالوج المرجعي المطلق: أي نتيجة من مصادر الكشط الخارجية لا يمكن ربطها بعمل رسمي في TMDB يتم استبعادها (`UNRESOLVED_TMDB_MAPPING: Unmatched provider media omitted from canonical TMDB catalog`).
   - في حالة المحتوى المترجم: يُعرض عنوان TMDB الرسمي دائماً.
   - في حالة المحتوى المدبلج المؤكد: يُعرض عنوان المصدر المخصص (مثل "المحقق كونان" بدلاً من الاسم الإنجليزي) كعنوان عرض (`displayTitle = candidateTitle ?: canonicalTitle`) مع الاحتفاظ بهوية TMDB الأصلية ورقم المعرف الرسمي (`canonicalTmdbId`).
5. **قاعدة الأنمي الصارمة:**
   - يمنع التطبيق دبلجة الأنمي نهائياً عبر فحص قطعي في `MediaVariantIdentity`:
     `if (isAnime && mode == MediaVariantMode.DUBBED) throw IllegalArgumentException("Anime is strictly subtitle-only. Dubbing is not supported.")`

---

### E. هوية Firebase والحزم وتوثيق الاختلافات
تم فحص إعدادات الهوية ومطابقتها مع القيم المرجعية لتقرير التدقيق:

| المتغير | القيمة المرجعية السابقة | القيمة الحالية في User App | حالة التطابق | التفاصيل والملاحظات |
| :--- | :--- | :--- | :--- | :--- |
| **Firebase Project ID** | `cinestream-sulo` | `ai-studio-applet-webapp-bbb09` | **MISMATCH** | معرف المشروع في `firebase-applet-config.json` يخص بيئة الويب الحالية، بينما يذكر `firestore.rules` (سطر 9) الاسم `cinestream-sulo`. |
| **Firebase Project Number** | `979447256418` | `938184974903` | **MISMATCH** | مسجل في `firebase-applet-config.json` كـ `messagingSenderId: 938184974903`. |
| **Application ID / Package** | `com.aistudio.cinestream.xyzabc` | `com.aistudio.cinestream.bdbnfg` | **MISMATCH** | المعرف المسجل في `app/build.gradle.kts` هو `com.aistudio.cinestream.bdbnfg`. |
| **Namespace** | غير محدد | `com.example` | **NOTED** | محدد في `app/build.gradle.kts`. |
| **google-services.json** | متوقع وجوده في `app/` | غير موجود محلياً | **MISMATCH** | يعتمد بناء الأندرويد في Gradle على `MissingGoogleServicesStrategy.WARN` لتمكين التطوير بدون الملف المحلي. |

*ملاحظة إلزامية:* بناءً على تعليمات المرحلة، لم يتم إجراء أي تعديل على هذه القيم، وتم تسجيل هذا الاختلاف كبند يستوجب المواءمة الموحدة في المرحلة الثنائية التالية.

---

### F. التحقق من بقاء ميزة التعليقات معطلة
1. **واجهات المستخدم (UI):**
   - تم فحص شاشات التفاصيل `DetailsScreens.kt` وكامل مجلدات العرض: لا توجد أي مدخلات، أو حقول نصوص، أو أزرار لإرسال أو عرض التعليقات.
2. **المستودعات وقواعد البيانات (Data Layer):**
   - لا توجد أي دوال استعلام أو استماع أو إضافة على المسار `/comments` في أي ملف برمجيات داخل المشروع.
   - الإشارة الوحيدة للتعليقات تقتصر على `UserSecurityManager.kt` و `EconomyModels.kt` للتحقق من قاطع الميزة وإرجاع رسالة "الميزة قيد التطوير" أو "معطلة من قِبل الإدارة".
3. **قواعد الأمان (`firestore.rules`):**
   - لا يوجد أي تصريح بالوصول إلى `/comments`. المسار خاضع تلقائياً لقاعدة الحظر الشامل النهائي (`match /{document=**} { allow read, write: if false; }`).

---

## 5. مقارنة أدلة Admin وتكامل النظم

بناءً على قائمة التحقق المستخرجة من متطلبات الإدارة في العقد المشترك:
1. **في جانب البلاغات:** تطابق تام في الحالات الثلاث (`PENDING`, `RESOLVED`, `DISMISSED`)، وفي عزل وتجريد بيانات الحل الإدارية عند إنشاء المستخدم للبلاغ.
2. **في جانب طلبات Pro:** تطابق تام في الحالات الأربع (`PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`)، وفي حصر صلاحية إلغاء الطلب من المستخدم على حقول `status`, `cancelledAt`, `updatedAt` أثناء حالة `PENDING`.
3. **في جانب ترتيب البحث:** يتطابق التطبيق تماماً مع هيكل المستند في `/config/search_order`، ويمتلك مرونة فائقة بفضل دعمه التلقائي للتسميات البديلة (`arabicDubbed`, `arabicDub`, `arabic_dub`).
4. **في جانب الصلاحيات الحساسة:** يتطابق منطق التطبيق مع توقعات Admin؛ فالمستخدم لا يملك أي وسيلة لتعديل الرتبة أو رصيد النقاط أو تفعيل الاشتراك مباشرة من طرف العميل.

---

## 6. الاختلافات المثبتة والعناصر غير المحسومة (Discrepancies & Unresolved Items)

1. **اختلاف معرفات مشروع Firebase والحزمة (مثبت - Proven):**
   - المعرفات الحالية (`ai-studio-applet-webapp-bbb09` و `com.aistudio.cinestream.bdbnfg`) لا تطابق القيم المرجعية (`cinestream-sulo` و `com.aistudio.cinestream.xyzabc`).
2. **غياب ملف `app/google-services.json` (مثبت - Proven):**
   - التطبيق يبنى حالياً باستخدام إعدادات `firebase-applet-config.json` وتهيئة `MissingGoogleServicesStrategy.WARN`.
3. **مطابقة قواعد Firestore المنشورة على السحابة (غير محسوم - Unresolved):**
   - الملف المحلي `firestore.rules` مدقق ومحكم الصلاحيات بنسبة 100%، ولكن لا يمكن التأكد برمجياً من مطابقة القواعد المطبقة على خوادم الإنتاج السحابية دون جلسة نشر ومقارنة سحابية.

---

## 7. تأكيد عدم تغيير الكود أو الإعدادات
- **مؤكد:** لم يتم تعديل أي ملف Kotlin أو Java أو Gradle أو XML.
- **مؤكد:** لم يتم تعديل أو نشر قواعد `firestore.rules`.
- **مؤكد:** لم يتم تعديل أي متغيرات بيئية أو قيم SHA أو معرفات الحزم.
- **مؤكد:** تم الالتزام الصارم بحدود صلاحيات مرحلة التدقيق والتوثيق فقط.

---

## 8. نتيجة التدقيق النهائية (Audit Verdict)

# **PASS WITH LIMITATIONS**

### أسباب القرار:
1. **الاجتياز (PASS):** تطابق عقود البيانات، ونماذج البلاغات، وإلغاء طلبات الاشتراك، وقواعد البحث والدبلجة، وتعطيل التعليقات بنسبة 100% مع قواعد الأمان والعقد المشترك.
2. **القيود (LIMITATIONS):** وجود اختلاف في معرفات حزمة الأندرويد ومشروع Firebase بين بيئة التطوير الحالية والقيم المرجعية السابقة، وغياب ملف `google-services.json` الفعلي، وهو ما يتطلب خطوة مواءمة وتوحيد إعدادات في المرحلة المشتركة القادمة دون التأثير على صحة الشيفرة البرمجية الحالية.

---
*نهاية التقرير — بانتظار مرحلة المقارنة الثنائية لدمج أدلة تطبيقي User و Admin.*
