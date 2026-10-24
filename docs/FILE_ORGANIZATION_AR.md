# تنظيم ملفات المشروع — دليل عربي

أعيد ترتيب الوثائق وتقارير المراحل وأدوات الفحص في مجلدات واضحة. لم تُشغّل اختبارات Node أو Gradle أو Firebase Emulator، ولم يتم نشر قواعد Firebase.

## ما بقي في موضعه عمدًا
- `app/google-services.json` هو ملف إعداد Android النشط ويظل داخل `app/`.
- `firebase.json`, `.firebaserc`, و`firestore.rules` تبقى في جذر المشروع لأن إعداد Firebase CLI يعتمد على مساراتها.
- إعدادات Gradle و`debug.keystore` و`metadata.json` بقيت في مواضعها اللازمة.
- اختبارات Android تبقى في مسارات Gradle القياسية داخل `app/src/test` و`app/src/androidTest`.

## ما تم نقله
- مجموعات الاختبارات المساعدة إلى `qa/`.
- مرجع قفل الهوية إلى `config/firebase/`.
- تقارير المراحل إلى `docs/archive/phase-reports/`.
- العقود والتدقيق إلى `docs/firebase/contracts/` و`docs/firebase/audits/`.
- نسخ القواعد والمرشحات والنسخ الاحتياطية إلى `docs/firebase/rules-history/`.

كل التقارير والنسخ المرجعية حُفظت، ولم تُحذف. عُدلت مسارات المراجع النشطة التي تعتمد على الملفات المنقولة.
