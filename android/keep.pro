# boringmech R8 keep 규칙 — 앱 패키지는 이름·멤버 전부 보존한다.
# (Bridge의 @JavascriptInterface 메서드는 JS가 이름으로 호출하고,
#  매니페스트 컴포넌트도 이름이 필요하다. 라이브러리는 도달 가능성으로 축소.)
-keep class com.roomedia.boringmech.** { *; }
