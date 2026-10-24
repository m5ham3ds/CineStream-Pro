# تقرير إصلاح بناء Android — Phase 23.6

## السبب المؤكد في إعدادات المشروع

- كان `gradle-wrapper.properties` يشغّل Gradle 9.3.1.
- كان `.github/workflows/build.yml` يثبت Gradle 9.3.1 أيضًا.
- إصدار Android Gradle Plugin في `gradle/libs.versions.toml` هو 8.7.2، ووثائق Android تحدد Gradle 8.9 للإصدار 8.7 من الإضافة. لذا جرى توحيد إصدار Gradle على 8.9 بما يتوافق مع AGP 8.7.x.

مرجع Android الرسمي: https://developer.android.com/build/releases/about-agp

## التغييرات

1. تحديث رابط Gradle Wrapper من 9.3.1 إلى 8.9.
2. تحديث `gradle/actions/setup-gradle` في سير عمل GitHub إلى Gradle 8.9.
3. حذف خطوة Pre-cache التي كانت تستهدف مسار Gradle 9.3.1 القديم وتستخدم تنزيلًا غير مضمون، كي لا تبقى إعدادات متعارضة.
4. استبدال `./gradlew assembleDebug || gradle assembleDebug` بتنفيذ واحد هو `./gradlew --no-daemon --stacktrace assembleDebug` حتى لا يعاد بناء المشروع ثانيةً بنفس الخطأ، وتظهر تفاصيل الاستثناء كاملة في GitHub Actions.
5. لم أغيّر AGP أو `compileSdk = 36`؛ التحذير الظاهر بشأن compileSdk ليس وحده إثباتًا لسبب الفشل، وترقية AGP تتطلب تغييرًا أوسع لا داعي للمخاطرة به قبل ظهور سجل الخطأ الكامل.

## الاختبارات المتاحة بعد التعديل

- `npm run test:identity`: **نجح 5/5**.
- `npm run test:rules`: **نجح 7/8**؛ بقي الفشل السابق نفسه في فحص وجود المسار `/comments` على الرغم من منعه صراحةً بقواعد القراءة والكتابة. لم أغيّر قواعد الأمان ضمن إصلاح إعداد البناء.
- الاتساق بين Gradle Wrapper وإعداد Gradle في GitHub Actions: **متطابق — 8.9**.
- فحص أرشيف المشروع بعد إنشائه: يُجرى قبل التسليم.

## حدود التحقق

المقتطف الذي أُرسل من GitHub Actions لا يحتوي على رسالة `FAILURE: Build failed with an exception` أو رسالة `e:`/`error:` النهائية. سجلات GitHub الكاملة تتطلب تسجيل الدخول، والبيئة الحالية لا تملك اتصالًا بالشبكة لتنزيل Gradle أو الاعتماديات؛ لذلك لا أستطيع الادعاء بأن بناء APK اكتمل محليًا أو أن مشكلة أخرى في كود Kotlin مستبعدة. تم إصلاح عدم التوافق الواضح بإعداد Gradle، لكن لا أعدّ هذا دليلًا على نجاح البناء النهائي حتى يعاد تشغيل GitHub Actions.
