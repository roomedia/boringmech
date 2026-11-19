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
# Bouncy Castle의 CryptoServicePurpose는 enum이다. R8의 enum unboxing이
# 이 enum을 int로 바꾸면, DefaultServiceProperties 생성자의
# "params instanceof CryptoServicePurpose"가 원시 타입 I에 대한
# instance-of로 기록돼 ART 검증기가 클래스 전체를 거부한다 — 서명 경로
# (다이제스트의 cryptoServiceProperties가 이 클래스를 생성)에서 모든
# 서명이 VerifyError로 죽는다 (v0.3.33 실기기에서 확정). enum 자체를
# 보존해 unboxing을 막는다. 재발은 dex 게이트(dex_gate.py)가 잡는다.
-keep class org.bouncycastle.crypto.CryptoServicePurpose { *; }
