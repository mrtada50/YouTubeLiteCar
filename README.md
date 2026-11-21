# YouTube Lite Car

مشغل يوتيوب خفيف للشاشات الأفقية (Android 10، RAM 1GB). Kotlin + Room + YouTube IFrame Player الرسمي.

## الإعداد
1. Google Cloud Console: فعّل YouTube Data API v3 وأنشئ API Key (قيّده على Android بالحزمة `com.youtubelite.car` وعلى YouTube Data API فقط).
2. GitHub > Settings > Secrets > Actions: أضف `YT_API_KEY`.
3. OAuth (اختياري لقوائم الحساب): أنشئ OAuth Client من نوع Android بالحزمة `com.youtubelite.car` وبصمة SHA-1 للتوقيع الذي تثبّت به، وفعّل شاشة الموافقة وأضف نفسك كمستخدم اختبار. النطاق المستخدم: `youtube`.
4. توقيع Release (اختياري): أضف Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## البناء
أي Push يشغّل Actions: الاختبارات ثم APK ثم رفعه Artifact (Actions > آخر Run > Artifacts).
محلياً: انسخ `local.properties.example` إلى `local.properties` ثم `gradle assembleDebug`.

## قائمة اختبار يدوي على الجهاز
بحث، تشغيل فيديو، إيقاف/استئناف، صوت، ملء الشاشة، مفضلة، سجل، قائمة محلية، فصل الإنترنت أثناء البحث، إعادة تشغيل التطبيق، تسجيل الدخول/الخروج، إضافة فيديو لقائمة يوتيوب.
