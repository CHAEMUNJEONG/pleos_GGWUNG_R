# Android 빌드와 소스 위치

필요한 도구는 JDK 17, Android SDK Platform 35 / Build Tools 35, Gradle wrapper 8.10.2다.
SDK 위치는 `ANDROID_HOME` 또는 git에서 제외되는 `local.properties`의 `sdk.dir`로 지정한다.
Android 설정 폴더를 별도로 지정한다면 `ANDROID_USER_HOME` 한 가지만 사용한다.
서로 다른 `ANDROID_USER_HOME`과 `ANDROID_PREFS_ROOT`를 동시에 지정하면 AGP 초기화가 실패한다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux에서는 `bash ./gradlew testDebugUnitTest assembleDebug`를 실행한다.
SDK Maven 저장소는 `settings.gradle.kts`에서 관리한다. API 키나 사용자별 경로를 커밋하지 않는다.

| 내용 | 경로 |
| --- | --- |
| 프로젝트 플러그인 선언 | `build.gradle.kts` |
| 앱 설정과 SDK 의존성 | `app/build.gradle.kts` |
| 의존성 버전 | `gradle/libs.versions.toml` |
| 앱 소스 | `app/src/main/java/com/kkwongal/drive/` |
| 앱 Manifest·리소스 | `app/src/main/AndroidManifest.xml`, `app/src/main/res/` |
| 단위테스트 | `app/src/test/java/` |
| 디버그 APK | `app/build/outputs/apk/debug/app-debug.apk` |

2026-11-19 업로드의 루트 Kotlin·Manifest·리소스 파일을 위 경로로 옮겼다.
같은 기능을 두 경로에서 따로 수정하지 않도록 루트 중복 파일을 제거했다.
Vehicle 2.3.2, TextToSpeech 3.3.1, NaviHelper 3.0.5 설정과 기존 최신 기능을 유지한다.
글꼴 라이선스는 `licenses/AstaSans-OFL.txt`에 있다.

핵심 로직 단위테스트 통과와 APK 생성은 실제 차량·SDK 이벤트 수신 성공을 뜻하지 않는다.
목적지 도착, 경로 요청, 차량 속성, 권한 거부·복구는 에뮬레이터/실차에서 별도 확인한다.

## Rust 코어 · JNI 브리지 · 리그 서버 (`rust/`)

앱의 모든 핵심 계산·상태머신·저장소·시뮬레이션·통신(`kkwongal-core`), Android/호스트 JNI 브리지(`kkwongal-jni`), 그리고 로컬 주간 리그 서버(`kkwongal-server`)는 `rust/` Cargo 워크스페이스에 구현되어 있다.

| 내용 | 경로 |
| --- | --- |
| Rust 워크스페이스 | `rust/Cargo.toml` |
| 핵심 엔진 크레이트 | `rust/crates/kkwongal-core/` |
| JNI 브리지 크레이트 | `rust/crates/kkwongal-jni/` |
| 로컬 리그 서버 크레이트 | `rust/crates/kkwongal-server/` |
| Kotlin JNI 어댑터 | `app/src/main/java/com/kkwongal/drive/rust/RustBridge.kt` |
| Android 네이티브 라이브러리 | `app/src/main/jniLibs/{x86_64,arm64-v8a}/libkkwongal_jni.so` |
| Windows JVM 테스트용 JNI / 서버 바이너리 | `app/src/main/jniLibs/host-windows-x86_64/kkwongal_jni.dll`, `rust/bin/kkwongal-server.exe` |
| Rust + Android 일괄 빌드 스크립트 | `tools/Build-RustAndroid.ps1` |

사전 빌드된 `.so`/`.dll`/`.exe`가 포함되어 있어 Rust/NDK를 설치하지 않은 환경에서도 `.\gradlew.bat testDebugUnitTest assembleDebug`를 바로 실행할 수 있다. Rust 소스를 수정한 뒤 다시 빌드할 때는 다음을 실행한다.

```powershell
.\tools\Build-RustAndroid.ps1
.\gradlew.bat testDebugUnitTest assembleDebug
```
