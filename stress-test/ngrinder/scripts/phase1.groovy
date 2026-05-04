import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.*
import static org.hamcrest.Matchers.*
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import HTTPClient.NVPair
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Phase 1] Turnstile 시스템 통합 부하 테스트
 * * 시나리오:
 * 1. 유저 로그인 및 Identity 토큰 발급
 * 2. 특정 콘서트 대기열 진입 요청
 * 3. 대기열 상태 폴링 (최대 30회, 1초 간격)
 * 4. 활성화 시 좌석 정보 조회 (캐시 성능 확인)
 * * @author nGrinder Agent
 * @version 1.0
 */
@RunWith(GrinderRunner)
class TurnstileIntegratedTest {

    // 테스트 지표 측정을 위한 GTest 객체 정의
    public static GTest testLogin, testEntry, testStatus, testSeats
    public static HTTPRequest reqLogin, reqEntry, reqStatus, reqSeats
    public static String baseUrl = "http://host.docker.internal:8080"

    /**
     * 전체 테스트 프로세스 시작 전 실행 (공통 자원 및 테스트 객체 초기화)
     */
    @BeforeProcess
    public static void beforeProcess() {
        // 커넥션 타임아웃 설정 (6초)
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        // nGrinder 리포트에 표시될 테스트 이름 정의
        testLogin = new GTest(1, "1_Login_API")
        testEntry = new GTest(2, "2_Queue_Entry_API")
        testStatus = new GTest(3, "3_Queue_Status_API")
        testSeats = new GTest(4, "4_Get_Seats_API")

        reqLogin = new HTTPRequest()
        reqEntry = new HTTPRequest()
        reqStatus = new HTTPRequest()
        reqSeats = new HTTPRequest()

        // 각 HTTP 요청과 테스트 객체를 매핑하여 성능 기록 활성화
        testLogin.record(reqLogin)
        testEntry.record(reqEntry)
        testStatus.record(reqStatus)
        testSeats.record(reqSeats)
    }

    /**
     * 각 스레드(가상 유저) 시작 전 실행
     */
    @BeforeThread
    public void beforeThread() {
        // 실시간 통계 수집 지연 설정 (정확한 지표 측정 목적)
        grinder.statistics.delayReports = true
    }

    /**
     * 실제 부하 테스트 비즈니스 로직
     */
    @Test
    public void doTest() {
        // [Data] 더미 데이터를 기반으로 랜덤 유저 1만 명 중 한 명 선택
        int randomUserId = new Random().nextInt(10000) + 1
        String username = "user" + randomUserId
        long concertId = 1L
        long scheduleId = 1L

        // --- Step 1: 로그인 (인증 및 식별자 획득) ---
        String body = JsonOutput.toJson([username: username])
        NVPair[] jsonHeader = [new NVPair("Content-Type", "application/json")]

        def loginRes = reqLogin.POST(baseUrl + "/api/v1/auth/login", body.getBytes(), jsonHeader)
        if (loginRes.statusCode != 200) {
            grinder.logger.warn("Login Failed for user: ${username}, Status: ${loginRes.statusCode}")
            return
        }

        def loginJson = new JsonSlurper().parseText(loginRes.text)
        String identityToken = loginJson.accessToken

        // --- Step 2: 대기열 진입 (Redis 대기 번호표 수령) ---
        NVPair[] authHeader = [
                new NVPair("Content-Type", "application/json"),
                new NVPair("Authorization", "Bearer " + identityToken)
        ]
        String entryBody = JsonOutput.toJson([concertId: concertId])

        def entryRes = reqEntry.POST(baseUrl + "/api/v1/queue/entry", entryBody.getBytes(), authHeader)
        // 성공(200) 또는 생성됨(201) 확인
        assertThat(entryRes.statusCode, anyOf(is(200), is(201)))

        // --- Step 3: 대기열 상태 폴링 (진입 허용 여부 주기적 확인) ---
        boolean isActive = false
        String queueToken = null
        int retryCount = 0

        // 최대 30초(30회) 동안 ACTIVE 상태가 될 때까지 반복
        while (!isActive && retryCount < 30) {
            grinder.sleep(1000) // 실제 유저의 폴링 간격 재현

            NVPair[] getHeader = [new NVPair("Authorization", "Bearer " + identityToken)]
            def statusRes = reqStatus.GET(baseUrl + "/api/v1/queue/status?concertId=" + concertId, null, getHeader)
            def statusJson = new JsonSlurper().parseText(statusRes.text)

            if (statusJson.status == "ACTIVE") {
                isActive = true
                queueToken = statusJson.accessToken // 활성 유저용 임시 토큰 획득
                break
            }
            retryCount++
        }

        // --- Step 4: 좌석 조회 (비즈니스 로직 수행 및 캐시 성능 측정) ---
        if (isActive && queueToken != null) {
            NVPair[] queueHeader = [new NVPair("Authorization", "Bearer " + queueToken)]
            def seatsRes = reqSeats.GET(baseUrl + "/api/v1/schedules/${scheduleId}/seats", null, queueHeader)

            // 캐시 데이터가 정상적으로 반환되는지 확인
            assertThat(seatsRes.statusCode, is(200))
        } else {
            grinder.logger.warn("Polling Timeout: User ${username} did not become ACTIVE within 30s")
        }
    }
}