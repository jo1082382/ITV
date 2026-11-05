# ITV Jev 공동 테스트

Android와 기존 Worker를 함께 관리한다. 서버 이름과 D1은 기존 리소스를 재사용하며 새 DB를 만들지 않는다.
`src/worker.original.js`는 최초 가져온 Worker 원본이다. 운영 진입점은 `src/index.js`이며 `src/worker.js`의 처리기를 불러온다.
비밀값을 Git, APK, Gradle, URL 쿼리, 로그에 넣지 않는다.

## 운영 구성

- Worker: `itv-jev-relay`
- D1 binding: `DB`, DB: `itv-jev-test-db`
- 기존 Secrets 유지: `ITV_TEST_TOKEN`, `JEV_API_KEY`
- 모델: `jev-1.14.0`, 프롬프트: `itv-break-v2`
- UTC 기준 호출 제한: 전체 300회/일, 참가자 40회/일, 전체 20회/분. MANUAL·LEGACY도 포함.
- 이벤트 제한: 전체 5,000회/일. 중복 재전송은 추가 차감하지 않는다.
- `JEV_ENABLED=false`로 배포하면 신규 유료 호출만 중단된다. 기록 API와 기존 결과 조회는 유지된다.
- 제한은 **호출 시도 수**이며 원화/달러 예산 상한은 아니다. 토큰 길이·Jev 단가에 따라 실제 비용이 달라진다.
- 공통 토큰과 참가자 ID는 7명 내부 시험용이다. 참가자 ID만 바꾸어도 인증되는 개인 계정 구조가 아니다.

## 개발과 배포 (PowerShell)

이 디렉터리에서 실행한다. PowerShell 실행 정책을 바꾸지 않도록 `npm.cmd`/`npx.cmd`를 사용한다.

```powershell
npm.cmd ci
npm.cmd test
npx.cmd wrangler d1 migrations apply DB --local
npm.cmd run check
npx.cmd wrangler types
```

로컬 비밀값은 `.dev.vars.example`을 참고하여 Git에서 제외한 `.dev.vars`에 설정한다.
운영 Secret 원문을 다운로드하거나 공유하지 않는다. 로컬 개발 DB와 remote DB는 별개다.

운영 반영 전 계정·Worker·DB 식별자·현재 스키마와 버전을 확인한다.
운영 데이터가 있으면 Cloudflare D1 Time Travel/백업을 확인한 뒤 **추가형 마이그레이션**을 적용한다.

```powershell
npx.cmd wrangler whoami
npx.cmd wrangler deployments list
npx.cmd wrangler d1 migrations list DB --remote
npx.cmd wrangler d1 migrations apply DB --remote
npx.cmd wrangler deploy
```

Commit/Push는 배포가 아니다. 배포 후 `GET /health`와 앱의 인증·D1 확인을 수행한다.
문제 발생 시 Worker 버전을 롤백할 수 있다. 추가한 DB 열은 롤백 때문에 삭제하지 않는다.
원본 Worker에는 DB 바인딩이 없었으므로 최초 배포에서는 Wrangler의 기존 DB 바인딩이 추가된다.

## API v2

`Authorization: Bearer <ITV_TEST_TOKEN>`, `Content-Type: application/json`.
`GET /health`는 공개 상태 확인, `GET /api/status`는 인증·D1 확인이며 Jev를 호출하지 않는다.

`POST /api/jev/evaluate` 예시 (비밀값 없음):

```json
{
  "protocol_version": 2, "id": "unique-uuid", "tester_id": "T01", "source": "MANUAL",
  "session_id": "session-uuid", "package_name": "com.instagram.android", "app_version": "1.0",
  "session_minutes": 18, "continuous_minutes": 18, "returns_10m": 4,
  "returns_observed_seconds": 600, "bedtime_goal_enabled": true, "bedtime_passed_minutes": 20,
  "observed_at": "<현재 ISO-8601 시각>", "trigger_reason": "MANUAL_INPUT",
  "settings": {"probabilityThreshold": 0.8, "revision": 1}
}
```

결과: `id,status,cached,probability,model,input_tokens,prompt_version`.
기존 3필드 요청은 LEGACY로 호환하지만 요청 ID가 없어 중복 복구가 불가능하다. 앱은 반드시 v2를 사용한다.
취침 목표 미설정은 `bedtime_goal_enabled=false, bedtime_passed_minutes=null`.
기존 NOT NULL 열에는 호환상 0을 저장하되, 명시적인 disabled 플래그와 context_json의 null로 결측을 구분한다.
시각 오차가 5분을 넘는 새 v2 요청은 거절한다. 스마트폰 시각 자동 설정을 권장한다.

같은 ID·같은 입력의 재요청은 저장된 결과만 반환한다. 다른 입력으로 ID를 재사용하면 409다.
PENDING은 202, 호출 한도는 429, Jev 중단/구성 오류는 503, 상류 오류는 502다.
상류 타임아웃은 과금 여부가 불명확하므로 UNKNOWN으로 남기며 자동 재호출하지 않는다.
Worker가 호출 도중 종료되면 PENDING이 남을 수 있다. 동일 ID 확인은 새 유료 호출을 하지 않는다.
아직 서버에 도착하지 않은 요청의 재전송은 최초 요청을 실행할 수 있으나, 앱은 재확인 결과로 개입하지 않는다.

`POST /api/events`:
```json
{
  "protocol_version": 2, "event_id": "unique-operation-uuid",
  "intervention_id": "evaluation-id", "tester_id": "T01", "source": "LIVE",
  "event_type": "feedback", "occurred_at": "<ISO-8601>",
  "data": {"score": 0, "feedback_status": "SUBMITTED"}
}
```

유형: displayed, suppressed, response, feedback, candidate, session_summary, mission.
응답 행동: REST, LATER, DISMISS, NO_RESPONSE, CANCELLED.
건너뛰기는 `score:null,feedback_status:SKIPPED`. 실제 displayed가 있어야 평가를 받는다.
최초 완료 평가를 유지하고 0점과 NULL을 구분한다.
같은 이벤트 ID의 동일 재전송은 1회 저장, 내용이 다르면 409다.
개입하지 않은 관측 요약은 intervention_events에만 남을 수 있으므로 두 테이블을 함께 분석한다.

## 팀원 기능과 통합

원본 master의 시간대별 숏폼 기록, 상세 통계, 캐릭터·보상·미션 재시도, 로컬 MLP 진단을 유지한다.
자동 관측에서 Jev를 켜면 Jev 경로가 실제 신규 개입 판단을 담당하고 MLP는 기존 진단 기능으로 남는다.
사용자 응답 대기는 팀원 버전의 45초를 사용한다. 최초 표시 전 Jev 결과 유효시간(기본 30초)과 구분한다.
반응 애니메이션이 끝난 뒤 화면을 제거하고 15초 휴식 준비 시간을 시작한다. 작성·잠금·상황 변경 시 취소한다.
평가는 애니메이션·미션·재시도 안내가 끝난 후에 표시하며 같은 개입에 중복 평가를 만들지 않는다.
로컬 이력은 RULE/JEV를 구분하고 Jev 미션 단계는 원래 판단 ID로 D1 이벤트에 연결한다.
기존 itv.db는 팀원 버전의 4→5→6→7 마이그레이션을 사용하고 Jev 테스트 DB는 별도로 유지한다.
Worker API 변경이나 재배포는 필요하지 않다.

## 앱 사용

`teamTest` APK는 `com.example.itv.teamtest`로 기존 앱과 별도 설치된다.
팀 테스트는 감시·실제 개입·외부 전송 동의가 모두 기본 OFF다.
릴리스에서는 Jev 메뉴·실행을 비활성화한다. 기존 로컬 30초 휴식 미션은 유지한다.

1. 홈 설정 메뉴 → Jev 공동 테스트.
2. 안내 확인, T01~T07 선택, 동의 저장. 테스트 토큰을 앱에 직접 입력·저장.
3. 연결 탭의 인증·D1 확인. JEV_API_KEY는 앱에 입력하지 않는다.
4. 수동 탭에서 임의 값·프리셋·최근 관측값으로 요청. MANUAL은 실제 개입을 표시하지 않는다.
5. 설정 탭에서 후보 조건·확률·유효시간·재평가·보류·기기 한도를 저장.
6. 자동 시험은 기존 사용량 동의 및 사용 정보·접근성·오버레이·알림 등 권한을 허용하고 관측을 활성화.
7. 실제 개입을 켜면 확률 통과 후 다음 숏폼 전환에서 현재 상태를 다시 확인하고 표시한다.
8. 기록 탭에서 미완료 평가, 호출 상태, 전송 대기/실패를 확인.

기본값: 연속 900초 OR 최근 10분 재진입 3회 OR 취침 목표 이후, 확률 ≥80%.
재평가는 최소 300초 **및** 추가 실제 시청 300초를 모두 요구한다.
응답은 요청 시작부터 30초간 유효하며 설정 변경·다른 앱/구간·작성·잠금·미션·보류를 재확인한다.
판단 중 장애가 나면 새 개입을 하지 않는다. 확률은 효과나 정답률을 의미하지 않으며 팀원 평가로 조정한다.

확인된 숏폼 관측 사이 1.5초 이하 간격만 누적한다. 미확인 구간은 보간하지 않는다.
재진입은 대상 앱 밖에서 돌아온 경우이며 최초 진입/앱 내부 이동은 제외한다.
새 사용 구간은 10분의 연속 확인된 이탈 후 시작한다. 프로세스 재시작으로 구간 억제를 풀지 않는다.
재부팅 중 남은 보류를 판단할 수 없으면 명시적 테스트 초기화 전까지 보류한다.
기록 탭의 초기화는 관측·보류만 초기화하고 서버 데이터·목표·호출 한도는 지우지 않는다.

실제 표시된 개입당 평가 팝업은 최대 1회, 점수 미선택 상태에서 제출할 수 없다.
REST는 화면을 15초 내 끄고 30초 쉬는 기존 미션을 방해하지 않도록 미션 종료 후 평가한다.
안전상 팝업이 닫히거나 서비스가 중단되어도 미완료 평가는 기록 탭에서 제출한다.

토큰은 Android Keystore AES-GCM으로 암호화하고 백업 제외 파일에 보관한다.
teamTest 앱 백업을 끈다. 이벤트는 별도 Room DB의 FIFO 대기열에 기록하며 앱/서비스 실행 중 재시도한다.
프로세스 종료 중에는 업로드하지 않고 다음 실행 시 이어간다. 자동 유료 판단 재시도는 하지 않는다.
동의 철회 시 새 수집·전송을 중단한다. 기존 서버 기록 삭제를 의미하지 않는다.

## 데이터 운영과 검증

보관기간·삭제 범위는 아직 확정되지 않았다. 자동 삭제/cron은 구현·배포하지 않는다.
7명에게 배포하기 전에 담당자가 수집 목적·항목·보관기간·삭제 요청 방법을 확정하여 안내하고 동의를 받아야 한다.
데이터 삭제는 확인된 정책과 승인 범위 내에서 별도로 수행한다.

```powershell
npx.cmd wrangler d1 execute DB --remote --command "SELECT tester_id,source,request_state,COUNT(*) AS n FROM intervention_tests GROUP BY tester_id,source,request_state;"
npx.cmd wrangler d1 execute DB --remote --command "SELECT id,final_intervene,user_action,feedback_score,feedback_status FROM intervention_tests ORDER BY recorded_at DESC LIMIT 20;"
npx.cmd wrangler d1 execute DB --remote --command "SELECT event_type,COUNT(*) AS n FROM intervention_events GROUP BY event_type;"
```

분석 시 MANUAL/LEGACY와 LIVE를 구분하고, 설정·모델·프롬프트 버전을 함께 본다.
동의 없는 실제 사용자 시험은 진행하지 않는다. 화면·메시지·영상 원문은 전송하지 않는다.

검증 명령:
```powershell
npm.cmd test
npm.cmd run check
# 프로젝트 루트
.\gradlew.bat :app:testTeamTestUnitTest :app:assembleTeamTest :app:assembleRelease :app:assembleQaAndroidTest
# 테스트 기기 연결 후 (QA 전용)
.\gradlew.bat :app:connectedQaAndroidTest
```

Node 테스트는 Node 22.13+의 내장 SQLite를 사용하고 Jev는 mock한다.
실기기 숏폼 인식·오버레이·REST 평가·인증된 실제 Jev→D1 전체 흐름은 별도 확인이 필요하다.

참고: [D1 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/),
[D1 마이그레이션](https://developers.cloudflare.com/d1/reference/migrations/),
[Workers 제한](https://developers.cloudflare.com/workers/platform/limits/).
