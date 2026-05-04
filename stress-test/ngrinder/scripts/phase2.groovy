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
 * [Phase 2] Turnstile 좌석 선점 및 결제 동시성 부하 테스트
 * * * 시나리오:
 * 1. 유저 로그인 및 대기열 통과 (Phase 1 흐름 포함)
 * 2. 활성 유저들이 무작위 좌석(1~50번)에 대해 동시 예약 시도
 * 3. 예약 성공 시 해당 건에 대한 최종 결제 요청
 * * * 중점 검증 사항:
 * - DB Lock 및 동시성 제어 로직의 정합성 (중복 예약 발생 여부)
 * - 쓰기 작업 집중 시 응답 지연 및 에러율 측정
 * * @author nGrinder Agent
 * @version 1.0
 */
@RunWith(GrinderRunner)
class TurnstileTicketingTest {

    public static GTest testReserve, testPayment
    public static HTTPRequest reqReserve, reqPayment
    public static String baseUrl = "http://host.docker.internal:8080"

    /**
     * 전체 테스트 프로세스 시작 전 실행 (테스트 지표 및 요청 객체 초기화)
     */
    @BeforeProcess
    public static void beforeProcess() {
        // 네트워크 타임아웃 설정 (쓰기 작업 대기를 고려하여 6초 설정)
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        // nGrinder 리포트 내 표시될 쓰기 작업 API 정의
        testReserve = new GTest(5, "5_Reserve_Seats_API")
        testPayment = new GTest(6, "6_Payment_API")

        reqReserve = new HTTPRequest()
        reqPayment = new HTTPRequest()

        // 지표 측정을 위해 요청 객체 기록 시작
        testReserve.record(reqReserve)
        testPayment.record(reqPayment)
    }

    /**
     * 각 스레드(가상 유저) 시작 전 실행
     */
    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
    }

    /**
     * 좌석 선점 및 결제 프로세스 실행
     */
    @Test
    public void doTest() {
        // [Data Setup] 테스트에 빙의할 랜덤 유저 및 콘서트 정보 설정
        int randomUserId = new Random().nextInt(10000) + 1
        String username = "user${randomUserId}"
        long concertId = 1L

        // --- Step 1 & 2: 로그인 및 대기열 진입 (Phase 1 기초 로직) ---
        String loginBody = JsonOutput.toJson([username: username])
        NVPair[] jsonHeader = [new NVPair("Content-Type", "application/json")]
        def loginRes = new HTTPRequest().POST("${baseUrl}/api/v1/auth/login", loginBody.getBytes(), jsonHeader)
        if (loginRes.statusCode != 200) return

        String identityToken = new JsonSlurper().parseText(loginRes.text).accessToken

        NVPair[] authHeader = [
                new NVPair("Content-Type", "application/json"),
                new NVPair("Authorization", "Bearer ${identityToken}")
        ]
        new HTTPRequest().POST("${baseUrl}/api/v1/queue/entry", JsonOutput.toJson([concertId: concertId]).getBytes(), authHeader)

        // --- Step 3: 대기열 상태 폴링 (ACTIVE 상태 확인) ---
        boolean isActive = false
        String queueToken = null
        for (int i = 0; i < 30; i++) {
            grinder.sleep(500) // 동시성 테스트를 위해 폴링 간격 단축
            def statusRes = new HTTPRequest().GET("${baseUrl}/api/v1/queue/status?concertId=${concertId}", null, [new NVPair("Authorization", "Bearer ${identityToken}")] as NVPair[])
            def statusJson = new JsonSlurper().parseText(statusRes.text)

            if (statusJson.status == "ACTIVE") {
                isActive = true
                queueToken = statusJson.accessToken
                break
            }
        }

        // 대기열을 통과하지 못한 유저는 이후 시나리오 진행 불가
        if (!isActive) return

        // --- Step 5: 좌석 예약 시도 (동시성 전쟁 발발 지점) ---
        // 1~50번 좌석 중 하나를 무작위로 선택하여 예약 요청
        long randomSeatId = new Random().nextInt(50) + 1
        String reserveBody = JsonOutput.toJson([concertId: concertId, seatIds: [randomSeatId]])
        NVPair[] queueAuthHeader = [
                new NVPair("Content-Type", "application/json"),
                new NVPair("Authorization", "Bearer ${queueToken}")
        ]

        def reserveRes = reqReserve.POST("${baseUrl}/api/v1/reservations", reserveBody.getBytes(), queueAuthHeader)

        /**
         * [Business Logic]
         * 200(성공) 뿐만 아니라 409(이미 선점됨)도 비즈니스적으로 정상적인 응답이므로
         * 테스트 실패(Error)로 간주하지 않도록 설정함
         */
        assertThat(reserveRes.statusCode, anyOf(is(200), is(409)))

        // --- Step 6: 예약 성공자만 결제 진행 (최종 트랜잭션 검증) ---
        if (reserveRes.statusCode == 200) {
            def reserveJson = new JsonSlurper().parseText(reserveRes.text)
            String groupId = reserveJson.groupId
            String paymentToken = reserveJson.accessToken

            String paymentBody = JsonOutput.toJson([groupId: groupId, amount: 150000, concertId: concertId])
            NVPair[] paymentAuthHeader = [
                    new NVPair("Content-Type", "application/json"),
                    new NVPair("Authorization", "Bearer ${paymentToken}")
            ]

            def paymentRes = reqPayment.POST("${baseUrl}/api/v1/payments", paymentBody.getBytes(), paymentAuthHeader)

            // 결제 성공 여부 확인
            assertThat(paymentRes.statusCode, is(200))
        }
    }
}