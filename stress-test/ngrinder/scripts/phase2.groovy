import static net.grinder.script.Grinder.grinder
import net.grinder.script.GTest
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
import java.util.concurrent.ThreadLocalRandom

/**
 * 좌석 선점 및 결제 동시성 테스트 (Phase 2)
 * 흐름: 로그인 -> 대기열 ACTIVE 폴링 -> 좌석 조회 -> 선점(경쟁 유도) -> 결제
 */
@RunWith(GrinderRunner)
class TurnstileTicketingTest {

    public static GTest testLogin, testEntry, testStatus, testSeats, testReserve, testPayment
    public static HTTPRequest reqLogin, reqEntry, reqStatus, reqSeats, reqReserve, reqPayment

    public static String baseUrl = "http://172.21.112.1:8080"

    @BeforeProcess
    public static void beforeProcess() {
        // 장시간 폴링(최대 60초)을 고려하여 커넥션 타임아웃 연장
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        testLogin = new GTest(1, "1_Login_API")
        testEntry = new GTest(2, "2_Queue_Entry_API")
        testStatus = new GTest(3, "3_Queue_Status_API")
        testSeats = new GTest(4, "4_Get_Seats_API")
        testReserve = new GTest(5, "5_Reserve_Seats_API")
        testPayment = new GTest(6, "6_Payment_API")

        reqLogin = new HTTPRequest()
        reqEntry = new HTTPRequest()
        reqStatus = new HTTPRequest()
        reqSeats = new HTTPRequest()
        reqReserve = new HTTPRequest()
        reqPayment = new HTTPRequest()

        testLogin.record(reqLogin)
        testEntry.record(reqEntry)
        testStatus.record(reqStatus)
        testSeats.record(reqSeats)
        testReserve.record(reqReserve)
        testPayment.record(reqPayment)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
    }

    @Test
    public void doTest() {
        try {
            int randomUserId = ThreadLocalRandom.current().nextInt(1, 9001)
            String username = "user${randomUserId}"
            long concertId = 1L
            long scheduleId = 1L

            // Step 1: 로그인
            def loginRes = reqLogin.POST("${baseUrl}/api/v1/auth/login",
                    JsonOutput.toJson([username: username]).getBytes(),
                    [new NVPair("Content-Type", "application/json")] as NVPair[])
            if (loginRes.statusCode != 200) {
                grinder.logger.warn(
                        "Login failed - user: ${username}, status: ${loginRes.statusCode}"
                )
                grinder.sleep(500)
                return
            }
            String identityToken = new JsonSlurper().parseText(loginRes.text).accessToken

            // Step 2: 대기열 등록 (Redis Sorted Set)
            def entryRes = reqEntry.POST("${baseUrl}/api/v1/queue/entry",
                    JsonOutput.toJson([concertId: concertId]).getBytes(),
                    [new NVPair("Authorization", "Bearer ${identityToken}"), new NVPair("Content-Type", "application/json")] as NVPair[])
            if (entryRes.statusCode != 200 && entryRes.statusCode != 201) {
                grinder.logger.warn(
                        "Queue entry failed - user: ${username}, status: ${entryRes.statusCode}"
                )
                grinder.sleep(500)
                return
            }

            // Step 3: 대기열 ACTIVE 상태 폴링 (최대 30초)
            boolean isActive = false
            String queueToken = null
            for (int i = 0; i < 60; i++) {
                grinder.sleep(500)
                def statusRes = reqStatus.GET("${baseUrl}/api/v1/queue/status?concertId=${concertId}", null,
                        [new NVPair("Authorization", "Bearer ${identityToken}")] as NVPair[])
                if (statusRes.statusCode != 200) {
                    continue
                }

                def statusJson = new JsonSlurper().parseText(statusRes.text)
                if (statusJson.status == "ACTIVE") {
                    isActive = true
                    queueToken = statusJson.accessToken
                    break
                }
            }
            if (!isActive) {
                grinder.logger.warn(
                        "Polling timeout (ACTIVE 전환 실패) after 60 retries - user: ${username}, concertId: ${concertId}"
                )
                return
            }

            // Step 4: 좌석 조회
            def seatsRes = reqSeats.GET("${baseUrl}/api/v1/schedules/${scheduleId}/seats", null,
                    [new NVPair("Authorization", "Bearer ${queueToken}")] as NVPair[])
            if (seatsRes.statusCode != 200) {
                grinder.logger.warn(
                        "Seat lookup failed - user: ${username}, status: ${seatsRes.statusCode}"
                )
                return
            }

            // Step 5: 좌석 예약 (상위 50개 좌석에 요청을 집중시켜 동시성 충돌 유도)
            int seatCount = ThreadLocalRandom.current().nextInt(1, 5)
            long startSeatId = ThreadLocalRandom.current().nextLong(1, 51 - seatCount)

            List<Long> targetSeatIds = []
            for (int j = 0; j < seatCount; j++) {
                targetSeatIds.add(startSeatId + j)
            }

            String reserveBody = JsonOutput.toJson([concertId: concertId, seatIds: targetSeatIds])
            def reserveRes = reqReserve.POST("${baseUrl}/api/v1/reservations", reserveBody.getBytes(),
                    [new NVPair("Content-Type", "application/json"), new NVPair("Authorization", "Bearer ${queueToken}")] as NVPair[])

            // 409(이미 선점됨)는 동시성 경쟁에 따른 정상 응답으로 처리
            if (![200, 409].contains(reserveRes.statusCode)) {
                grinder.logger.warn(
                        "Reservation failed - user: ${username}, status: ${reserveRes.statusCode}"
                )
                return
            }

            // Step 6: 결제 처리 (예약 성공한 유저만 진입)
            if (reserveRes.statusCode == 200) {
                def reserveJson = new JsonSlurper().parseText(reserveRes.text)
                long totalAmount = 150000L * targetSeatIds.size()
                String paymentBody = JsonOutput.toJson([groupId: reserveJson.groupId, amount: totalAmount, concertId: concertId])

                def paymentRes = reqPayment.POST("${baseUrl}/api/v1/payments", paymentBody.getBytes(),
                        [new NVPair("Content-Type", "application/json"), new NVPair("Authorization", "Bearer ${reserveJson.accessToken}")] as NVPair[])

                if (paymentRes.statusCode != 200) {
                    grinder.logger.warn(
                            "Payment failed - user: ${username}, status: ${paymentRes.statusCode}"
                    )
                }
            }
            grinder.sleep(1000)  // 실제 유저가 좌석을 둘러보는 잔류 시간 시뮬레이션
        } catch (Exception e) {
            grinder.logger.error("Unexpected ticketing flow error", e)
            grinder.sleep(1000)
        }
    }
}