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
 * [Phase 2] Turnstile 좌석 선점 및 결제 동시성 테스트 (Optimized)
 * 시나리오: 로그인 -> 진입 -> 폴링 -> 좌석조회(Step 4 추가) -> 예약 -> 결제
 */
@RunWith(GrinderRunner)
class TurnstileTicketingTest {

    public static GTest testReserve, testPayment
    public static HTTPRequest reqLogin, reqEntry, reqStatus, reqSeats, reqReserve, reqPayment
    public static String baseUrl = "http://host.docker.internal:8080"

    @BeforeProcess
    public static void beforeProcess() {
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        // 핵심 측정 지표: 예약(Step 5)과 결제(Step 6)
        testReserve = new GTest(5, "5_Reserve_Seats_API")
        testPayment = new GTest(6, "6_Payment_API")

        // 모든 객체 사전 생성
        reqLogin = new HTTPRequest()
        reqEntry = new HTTPRequest()
        reqStatus = new HTTPRequest()
        reqSeats = new HTTPRequest()
        reqReserve = new HTTPRequest()
        reqPayment = new HTTPRequest()

        testReserve.record(reqReserve)
        testPayment.record(reqPayment)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
    }

    @Test
    public void doTest() {
        int randomUserId = new Random().nextInt(10000) + 1
        String username = "user${randomUserId}"
        long concertId = 1L
        long scheduleId = 1L

        // --- Step 1 & 2: 선행 절차 (재사용 객체 사용) ---
        def loginRes = reqLogin.POST("${baseUrl}/api/v1/auth/login", JsonOutput.toJson([username: username]).getBytes(), [new NVPair("Content-Type", "application/json")] as NVPair[])
        if (loginRes.statusCode != 200) return
        String identityToken = new JsonSlurper().parseText(loginRes.text).accessToken

        reqEntry.POST("${baseUrl}/api/v1/queue/entry", JsonOutput.toJson([concertId: concertId]).getBytes(), [new NVPair("Authorization", "Bearer ${identityToken}"), new NVPair("Content-Type", "application/json")] as NVPair[])

        // --- Step 3: 대기열 폴링 (동시성 유도를 위해 0.5초 간격) ---
        boolean isActive = false
        String queueToken = null
        for (int i = 0; i < 30; i++) {
            grinder.sleep(500)
            def statusRes = reqStatus.GET("${baseUrl}/api/v1/queue/status?concertId=${concertId}", null, [new NVPair("Authorization", "Bearer ${identityToken}")] as NVPair[])
            def statusJson = new JsonSlurper().parseText(statusRes.text)
            if (statusJson.status == "ACTIVE") {
                isActive = true
                queueToken = statusJson.accessToken
                break
            }
        }
        if (!isActive) return

        // --- Step 4: 좌석 조회 (실제 유저 흐름 반영 및 캐시 검증) ---
        reqSeats.GET("${baseUrl}/api/v1/schedules/${scheduleId}/seats", null, [new NVPair("Authorization", "Bearer ${queueToken}")] as NVPair[])

        // --- Step 5: 좌석 예약 시도 (핵심 측정 대상) ---
        long randomSeatId = new Random().nextInt(500) + 1
        String reserveBody = JsonOutput.toJson([concertId: concertId, seatIds: [randomSeatId]])
        NVPair[] queueAuthHeader = [
                new NVPair("Content-Type", "application/json"),
                new NVPair("Authorization", "Bearer ${queueToken}")
        ]
        def reserveRes = reqReserve.POST("${baseUrl}/api/v1/reservations", reserveBody.getBytes(), queueAuthHeader)

        // 200(성공) 또는 409(이미 선점됨) 확인[cite: 1, 2]
        assertThat("예약 API 서버 오류", reserveRes.statusCode, anyOf(is(200), is(409)))

        // --- Step 6: 예약 성공자 대상 최종 결제 (핵심 측정 대상) ---
        if (reserveRes.statusCode == 200) {
            def reserveJson = new JsonSlurper().parseText(reserveRes.text)
            String paymentBody = JsonOutput.toJson([groupId: reserveJson.groupId, amount: 150000, concertId: concertId])

            def paymentRes = reqPayment.POST("${baseUrl}/api/v1/payments", paymentBody.getBytes(),
                    [new NVPair("Content-Type", "application/json"), new NVPair("Authorization", "Bearer ${reserveJson.accessToken}")] as NVPair[])

            assertThat("결제 트랜잭션 실패", paymentRes.statusCode, is(200))
        }
    }
}