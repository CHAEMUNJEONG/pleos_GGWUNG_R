# Android 빌드와 소스 위치

필요한 도구는 JDK 17, Android SDK Platform 35 / Build Tools 35, Android NDK (API 28 이상 지원, 예: NDK 28), Rust 툴체인(`cargo`, `rustup target add x86_64-linux-android aarch64-linux-android`), Gradle wrapper 8.10.2다.
SDK 위치는 `ANDROID_HOME` 또는 git에서 제외되는 `local.properties`의 `sdk.dir`로 지정하고, NDK 위치는 `ANDROID_NDK_HOME` 환경변수(또는 `ANDROID_HOME/ndk/<버전>`)로 지정한다.
Android 설정 폴더를 별도로 지정한다면 `ANDROID_USER_HOME` 한 가지만 사용한다.
서로 다른 `ANDROID_USER_HOME`과 `ANDROID_PREFS_ROOT`를 동시에 지정하면 AGP 초기화가 실패한다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux에서는 `bash ./gradlew testDebugUnitTest assembleDebug`를 실행한다.
SDK Maven 저장소는 `settings.gradle.kts`에서 관리한다. API 키나 사용자별 경로, 빌드된 바이너리(`.so`, `.dll`, `.exe`)는 커밋하지 않는다.

| 내용 | 경로 |
| --- | --- |
| 프로젝트 플러그인 선언 | `build.gradle.kts` |
| 앱 설정과 SDK·Rust 빌드 태스크 | `app/build.gradle.kts` |
| 의존성 버전 | `gradle/libs.versions.toml` |
| 앱 소스 | `app/src/main/java/com/kkwongal/drive/` |
| Kotlin JNI 어댑터 | `app/src/main/java/com/kkwongal/drive/rust/RustBridge.kt` |
| 앱 Manifest·리소스 | `app/src/main/AndroidManifest.xml`, `app/src/main/res/` |
| 단위테스트 | `app/src/test/java/` |
| Rust Cargo 워크스페이스 | `rust/Cargo.toml` |
| Rust 핵심 엔진 크레이트 | `rust/crates/kkwongal-core/` |
| Rust JNI 브리지 크레이트 | `rust/crates/kkwongal-jni/` |
| Rust 로컬 리그 서버 크레이트 | `rust/crates/kkwongal-server/` |
| 디버그 APK | `app/build/outputs/apk/debug/app-debug.apk` |

2026-11-19 업로드의 루트 Kotlin·Manifest·리소스 파일을 위 경로로 옮겼다.
같은 기능을 두 경로에서 따로 수정하지 않도록 루트 중복 파일을 제거했다.
Vehicle 2.3.2, TextToSpeech 3.3.1, NaviHelper 3.0.5 설정과 기존 최신 기능을 유지한다.
글꼴 라이선스는 `licenses/AstaSans-OFL.txt`에 있다.

## Rust 자동 빌드 연동

- `app/build.gradle.kts`의 `cargoBuildHost` 및 `cargoBuildAndroid` 태스크가 `rust/Cargo.toml`, `rust/Cargo.lock`, `rust/crates/**`의 변경을 추적하여 `testDebugUnitTest`, `assembleDebug`, `assembleRelease` 실행 시 자동으로 최신 Rust 소스를 컴파일한다.
- 따라서 저장소에 `.so`, `.dll`, `.exe` 바이너리나 개인 PC 경로가 적힌 `rust/.cargo/config.toml`을 커밋하지 않으며, Rust 소스를 수정하면 다음 Gradle 빌드에서 항상 최신 네이티브 라이브러리가 반영된다.
- Rust 단위·서버 테스트와 네이티브 빌드를 단독으로 실행할 때는 다음 스크립트를 사용할 수 있다.

```powershell
.\tools\Build-RustAndroid.ps1
```

핵심 로직 단위테스트 통과와 APK 생성은 실제 차량·SDK 이벤트 수신 성공을 뜻하지 않는다.
목적지 도착, 경로 요청, 차량 속성, 권한 거부·복구는 에뮬레이터/실차에서 별도 확인한다.
