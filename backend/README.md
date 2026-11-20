# 로컬 개발용 주간 리그 API

Python 3.11 이상, 표준 라이브러리와 SQLite만 사용한다. 서버는 `127.0.0.1`에만 열린다.
기본 빌드는 기존 가상 리그를 유지하며, 서버 주소를 명시한 빌드만 서버 모드를 사용한다.

## 실행

저장소 루트에서 계정을 만든다. `python` 대신 Windows의 `py -3`를 사용해도 된다.

```powershell
python backend/server.py --create-user "개발 계정"
python backend/server.py
```

발급 JSON의 token은 앱의 주간 리그 화면에 입력한다. DB에는 토큰 해시만 저장하며
앱은 토큰을 세션 메모리에만 보관한다. 재시작하면 다시 입력한다.
계정 발급 명령을 다시 실행하면 별도 계정이 생성된다. 토큰은 PR·영상·로그에 포함하지 않는다.

DB는 git에서 제외되는 `backend/data/league-v2.sqlite3`에 생성한다.
이전 prototype-v1 DB와 점수를 섞지 않는다. 이전 DB를 직접 지정하면 명확히 거부한다.
운영 로그인·토큰 만료/폐기·차량 데이터 인증·공개 서버 배포는 이 개발 서버의 범위 밖이다.

## 앱 연결

같은 PC의 Android/Pleos AVD는 호스트 주소 `10.0.2.2`를 사용한다.

```powershell
.\gradlew.bat assembleDebug -PLEAGUE_API_BASE_URL=http://10.0.2.2:8765/ -PLEAGUE_LOCAL_HTTP=true
adb -s emulator-6000 install -r app/build/outputs/apk/debug/app-debug.apk
```

기기와 서버가 다른 PC에 있으면 이 주소를 그대로 쓰지 않는다. USB 기기에서는
`adb reverse tcp:8765 tcp:8765`와 `http://127.0.0.1:8765/`를 사용할 수 있다.
서버 URL은 끝에 `/`가 필요하다. 기본은 HTTPS이며 로컬 HTTP는 debug의 명시적 옵션과
허용된 localhost/127.0.0.1/10.0.2.2에서만 사용할 수 있다. release에는 이 HTTP 예외가 없다.

1. 주간 리그에서 접속 토큰을 입력하고 연결한다.
2. 실제/시뮬레이션 모드 중 하나에서 주행을 저장한다.
3. `최근 주행 1건 전송`을 누르면 현재 모드의 가장 최근 기록 1건만 전송한다.
4. 개별 1km 이상인 주행만 해당 모드 리그에 집계된다. 0.3~1km 미만은 저장하되 순위에 넣지 않는다.
5. 같은 기록을 다시 보내면 중복 집계하지 않는다. 전체 이력을 자동 전송하지 않는다.

전송 필드는 시간·거리·급가감속/급조향 횟수·최고속도·에코/종합 점수·시뮬레이션 여부다.
GPS·목적지명·스탬프·메모·전체 이벤트는 전송하지 않는다.
연결 실패 시 가상 사용자를 서버 결과처럼 표시하지 않는다. 기본 시연 모드와 서버 모드는 구분된다.

## 계약과 최신 점수 규칙

| 요청 | 결과 |
| --- | --- |
| `GET /health` | 인증 없는 개발 서버 상태 |
| `GET /v1/league/weekly?weekStartMs=...&simulated=false` | Bearer 인증 계정의 실제 주행 리그 |
| 동일 요청의 `simulated=true` | 별도 시뮬레이션 리그 |
| `POST /v1/trips` | Bearer 인증 계정의 기록 저장/중복 처리 |

POST의 필드는 `tripId`, `startTimeMs`, `endTimeMs`, `distanceKm`, `harshAccelCount`,
`harshBrakeCount`, `harshSteerCount`, `maxSpeedKmh`, `ecoScore`, `score`, `simulated`,
`scoringVersion`이며 버전은 `prototype-v2`다.

- 안전 점수는 급가속 4·급제동 5·급조향 4점을 거리당 정규화하며 최소 거리는 10km다.
- 종합 점수는 안전 40% + 에코 60%다. 반올림은 Kotlin의 비음수 `roundToInt`에 맞춘다.
- 개별 1km 이상 기록의 거리 가중 평균을 `myAverage`로 돌려준다.
- 순위 점수 `myLeagueScore`는 `(Σ점수×거리 + 80×20)/(Σ거리+20)`를 소수 1자리로 반올림한다.
- 순위표 항목의 `averageScore`는 이 보정된 순위 점수다. 원래 평균과 구분한다.
- 실제/시뮬레이션을 순위·누적 가상 포인트에서 분리한다. 동점은 서버 계정 ID 순이다.
- Asia/Seoul 월요일 00시 기준이며 신규 기록은 이번 주에 끝난 것만 받는다.
- 같은 계정·ID·본문 재전송은 200/duplicate, 다른 본문은 409다. 중복은 주가 바뀌어도 유지된다.
- 신규 저장 201, 미인증 401, 잘못된 JSON 400, 본문 크기 초과 413, 입력/점수/주 시작 시각 오류 422다.

에코 점수·거리·횟수는 클라이언트의 요약값을 신뢰하는 개발용 설계다. 실제 주행은 시간·최고속도·거리
일관성을 검사한다. 시간 배속 시연은 별도 시뮬레이션 리그에서만 거리/실시간 검사 예외를 적용한다.
서버가 GPS나 원시 차량 신호로 운전을 인증한 결과가 아니며 실제 현대포인트 지급 API는 없다.
이전 점수 방식으로 저장된 기록은 점수 불일치로 거부될 수 있으므로 새 버전에서 새 주행으로 확인한다.

## 검증

```powershell
python -m unittest discover -s backend -v
$env:PLEOS_BACKEND_PYTHON = (python -c "import sys; print(sys.executable)")
.\gradlew.bat testDebugUnitTest assembleDebug
```

Python 환경변수를 지정하면 Kotlin 클라이언트가 실제 Python/SQLite 서버를 시작해
인증·업로드·재전송·두 사용자 순위·Kotlin/서버 계산 일치·모드 분리를 검증한다.
지정하지 않으면 해당 계약 테스트는 건너뛰므로, 통합 검증 시 반드시 지정한다.
UI에서 토큰 입력·모드 변경·오프라인 복구도 별도로 확인한다.
