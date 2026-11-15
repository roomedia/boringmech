# boringmech R8 keep 규칙 — 앱 패키지는 이름·멤버 전부 보존한다.
# (Bridge의 @JavascriptInterface 메서드는 JS가 이름으로 호출하고,
#  매니페스트 컴포넌트도 이름이 필요하다. 라이브러리는 도달 가능성으로 축소.)
-keep class com.roomedia.boringmech.** { *; }
# 어노테이션을 보존해야 한다 — @JavascriptInterface가 벗겨지면 WebView가
# 브리지 메서드를 JS에 노출하지 않는다 (v0.3.26~v0.3.29 장애의 근본 원인).
-keepattributes *Annotation*
-keepclassmembers class com.roomedia.boringmech.** {
  @android.webkit.JavascriptInterface <methods>;
}
