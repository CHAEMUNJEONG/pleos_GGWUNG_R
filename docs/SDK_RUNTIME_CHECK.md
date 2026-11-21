# NaviHelper 실행 검증

`PleosSdkProbe`는 debug APK에만 들어가는 계측 도구다. 실제 앱의 `PleosNavi`를 연결해
위치·도착·제한속도 콜백을 관찰한 다음 해제한다. 이벤트나 위치를 만들어 넣지 않는다.
원시 좌표·목적지명·토큰은 결과에 저장하지 않는다. 연결 상태와 오류 문구는 공유 전 확인한다.

1. Pleos 에뮬레이터를 부팅하고 필요한 권한·ADB USB 디버깅을 승인한다.
2. `./gradlew testDebugUnitTest assembleDebug`로 debug APK를 빌드한다.
3. 기본 내비 앱을 열고 필요하면 경로를 준비한다.
4. PowerShell에서 실행한다. ADB가 PATH에 없으면 `-AdbPath`로 지정한다.

```powershell
.\tools\Invoke-PleosSdkProbe.ps1 -Serial emulator-6000 -WaitMs 20000
```

`serviceConnected=true`는 SDK 초기화 성공 콜백을 뜻한다. `locationCallbacks`,
`arrivalCallbacks`, `speedLimitCallbacks`는 관찰 중 들어온 실제 이벤트 수다.
0회인 이벤트는 검증 완료로 표시하지 않는다. 도착을 확인하려면 최대 60초 관찰 중
내비의 실제 경로 주행/도착 시나리오를 진행하고 스탬프 저장까지 별도로 확인한다.
실행 도구는 내비 경로·주행·GPS를 자동 조작하지 않는다.

결과는 git에서 제외되는 `sdk-probe-results/`에 저장된다. 공유 증빙에는 커밋 SHA,
에뮬레이터 이미지·내비 앱 버전·관찰 시각을 함께 기록한다. 이전 버전의 성공 로그는
최신 버전의 성공 증빙으로 재사용하지 않는다. 이 검증은 실차 검증을 대신하지 않는다.
